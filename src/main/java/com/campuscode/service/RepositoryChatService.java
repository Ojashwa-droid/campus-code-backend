package com.campuscode.service;

import com.campuscode.api.IngestionStatus;
import com.campuscode.api.RepositoryChatDtos;
import com.campuscode.api.RepositoryChatRequest;
import com.campuscode.persistence.IngestionJobEntity;
import com.campuscode.persistence.IngestionJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RepositoryChatService {
    private static final Logger log = LoggerFactory.getLogger(RepositoryChatService.class);
    private static final String INSUFFICIENT_EVIDENCE
            = "The retrieved repository context does not establish enough detail to answer that. " +
            "Try asking about a specific file, class, or behavior.";

    private final RestClient cognee;
    private final IngestionJobRepository ingestions;
    private final CogneeDatasetResolver datasetResolver;
    private final ChatClient chatClient;
    private final ExecutorService worker;
    private final ObjectMapper mapper;
    private final RepositoryChatContextBuilder contextBuilder;

    public RepositoryChatService(RestClient cognee, IngestionJobRepository ingestions,
                                 ChatClient.Builder chatClientBuilder, ExecutorService worker,
                                 ObjectMapper mapper, CogneeDatasetResolver datasetResolver,
                                 @Value("${campuscode.chat.context.max-estimated-tokens:12000}") int maxEstimatedTokens,
                                 @Value("${campuscode.chat.context.chars-per-token:3}") int charsPerToken) {
        this.cognee = cognee;
        this.ingestions = ingestions;
        this.chatClient = chatClientBuilder.build();
        this.worker = worker;
        this.mapper = mapper;
        this.datasetResolver = datasetResolver;
        this.contextBuilder = new RepositoryChatContextBuilder(maxEstimatedTokens, charsPerToken);
    }

    public SseEmitter stream(UUID ingestionId, RepositoryChatRequest request) {
        IngestionJobEntity ingestion = ingestions.findById(ingestionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository ingestion was not found."));
        if (ingestion.getStatus() != IngestionStatus.State.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Repository ingestion must complete before chat is available.");
        }
        SseEmitter emitter = new SseEmitter(600_000L);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Runnable dispose = () -> {
            Disposable current = subscription.get();
            if (current != null) current.dispose();
        };
        emitter.onCompletion(dispose);
        emitter.onTimeout(dispose);
        emitter.onError(ignored -> dispose.run());
        try {
            worker.submit(() -> runTurn(ingestion, request, emitter, subscription));
        } catch (RuntimeException exception) {
            log.warn("Could not schedule repository chat ingestionId={} error={}", ingestionId, exception.getMessage());
            sendError(emitter, "Repository chat is busy. Please retry shortly.");
        }
        return emitter;
    }

    private void runTurn(IngestionJobEntity ingestion, RepositoryChatRequest request, SseEmitter emitter,
                         AtomicReference<Disposable> subscription) {
        long started = System.nanoTime();
        try {
            String datasetId = ingestion.getCogneeDatasetId();
            if (!StringUtils.hasText(datasetId)) {
                datasetId = datasetResolver.resolveId(ingestion.getDatasetName());
                ingestion.setCogneeDatasetId(datasetId);
                ingestions.save(ingestion);
            }
            String query = request.query().strip();
            Object hybridResponse = retrieveHybrid(datasetId, query);
            List<RepositoryChatContextBuilder.Source> sources = passageSources(hybridResponse, ingestion.getRepositoryUrl());

            Object codeResponse = retrieveCode(datasetId, query, ingestion.getId());
            JsonNode graph = retrieveGraph(datasetId, ingestion.getId());
            if (graph != null && codeResponse != null) {
                RepositoryEvidence.CodeSearchSummary code = RepositoryEvidence.summarizeCodeSearch(codeResponse, mapper);
                RepositoryGraphExpander.Expansion expanded = RepositoryGraphExpander.expandDetailed(graph,
                        "repository-chat", query, codeResponse, hybridResponse, ingestion.getRepositoryUrl(),
                        Set.copyOf(code.seedNodeIds()));
                appendGraphSources(sources, expanded.facts());
            }

            List<RepositoryChatRequest.Turn> history = request.history() == null ? List.of() : request.history();
            RepositoryChatContextBuilder.BuiltContext context = contextBuilder.build(query, history, sources);
            log.info("Repository chat context ready ingestionId={} passageAndGraphSources={} selectedSources={} estimatedTokens={} durationMs={}",
                    ingestion.getId(), sources.size(), context.sources().size(), context.estimatedTokens(), elapsed(started));
            if (context.sources().isEmpty()) {
                sendEvent(emitter, "final", new RepositoryChatDtos.FinalAnswer(INSUFFICIENT_EVIDENCE));
                emitter.complete();
                return;
            }
            streamAnswer(ingestion, context, emitter, subscription, started);
        } catch (Exception exception) {
            log.warn("Repository chat failed ingestionId={} durationMs={} error={}", ingestion.getId(), elapsed(started), exception.getMessage(), exception);
            sendError(emitter, "Repository context could not be retrieved or the answer could not be generated. Check the services and try again.");
        }
    }

    private Object retrieveHybrid(String datasetId, String query) {
        Map<String, Object> request = hybridSearchRequest(datasetId, query);
        return cognee.post().uri("/api/v1/search").body(request).retrieve().body(Object.class);
    }

    private Object retrieveCode(String datasetId, String query, UUID ingestionId) {
        Map<String, Object> request = codeSearchRequest(datasetId, query);
        try {
            return cognee.post().uri("/api/v1/search").body(request).retrieve().body(Object.class);
        } catch (RuntimeException exception) {
            log.warn("Cognee CODE chat retrieval unavailable ingestionId={} status={}", ingestionId,
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    static Map<String, Object> hybridSearchRequest(String datasetId, String query) {
        return Map.of("query", query, "dataset_ids", List.of(datasetId), "search_type", "HYBRID_COMPLETION",
                "top_k", 8, "only_context", true, "verbose", true, "include_references", true);
    }

    static Map<String, Object> codeSearchRequest(String datasetId, String query) {
        return Map.of("query", query, "dataset_ids", List.of(datasetId), "search_type", "CODE",
                "code_query", Map.of("operation", "ask"), "top_k", 8, "only_context", true, "verbose", true);
    }

    private JsonNode retrieveGraph(String datasetId, UUID ingestionId) {
        try {
            return cognee.get().uri("/api/v1/datasets/{datasetId}/graph", datasetId)
                    .retrieve().body(JsonNode.class);
        } catch (RuntimeException exception) {
            log.warn("Cognee graph chat expansion unavailable ingestionId={} status={}", ingestionId,
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    private List<RepositoryChatContextBuilder.Source> passageSources(Object response, String repositoryUrl) {
        RepositoryEvidence.Extraction extraction = RepositoryEvidence.extractWithAudit(response, mapper, repositoryUrl);
        LinkedHashMap<String, RepositoryEvidence> unique = new LinkedHashMap<>();
        for (RepositoryEvidence passage : extraction.evidence()) {
            String key = passage.filePath().toLowerCase(java.util.Locale.ROOT) + "\n" + passage.text().strip();
            unique.putIfAbsent(key, passage);
        }
        List<RepositoryChatContextBuilder.Source> result = new ArrayList<>();
        for (RepositoryEvidence passage : unique.values()) {
            String id = "P" + (result.size() + 1);
            result.add(new RepositoryChatContextBuilder.Source(id, "COGNEE_RETRIEVED_PASSAGE",
                    passage.filePath(), positive(passage.startLine()), positive(passage.endLine()), null, null,
                    passage.text()));
        }
        if (result.isEmpty()) {
            String contextText = findContextText(mapper.valueToTree(response));
            if (StringUtils.hasText(contextText)) {
                result.add(new RepositoryChatContextBuilder.Source("P1", "COGNEE_CONTEXT_WITHOUT_SOURCE_PATH",
                        null, null, null, null, null, contextText));
            }
        }
        return result;
    }

    private static String findContextText(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) {
            String value = node.asText();
            return value.length() > 120_000 ? value.substring(0, 120_000) : value;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                String value = findContextText(child);
                if (StringUtils.hasText(value)) return value;
            }
        }
        if (node.isObject()) {
            for (String field : List.of("context_result", "context", "text_result")) {
                String value = findContextText(node.get(field));
                if (StringUtils.hasText(value)) return value;
            }
        }
        return null;
    }

    private static void appendGraphSources(List<RepositoryChatContextBuilder.Source> sources,
                                           List<com.campuscode.api.EvaluationDtos.GraphFact> facts) {
        Set<String> seen = new LinkedHashSet<>();
        sources.stream().filter(source -> source.id().startsWith("G")).forEach(source -> seen.add(source.id()));
        for (var fact : facts) {
            if (seen.contains(fact.id())) continue;
            String id = "G" + (1 + sources.stream().filter(source -> source.id().startsWith("G")).count());
            String name = StringUtils.hasText(fact.symbolName()) ? fact.symbolName() : fact.label();
            String excerpt = fact.description();
            if (fact.relationships() != null && !fact.relationships().isEmpty()) {
                excerpt += "\nRelationships: " + String.join("; ", fact.relationships());
            }
            Integer line = fact.line() == null ? null : positive(fact.line());
            sources.add(new RepositoryChatContextBuilder.Source(id, "COGNEE_GRAPH_METADATA", fact.filePath(),
                    line, line, name, fact.symbolType(), excerpt));
            seen.add(fact.id());
        }
    }

    private void streamAnswer(IngestionJobEntity ingestion, RepositoryChatContextBuilder.BuiltContext context,
                              SseEmitter emitter, AtomicReference<Disposable> subscription, long started) {
        StringBuilder generated = new StringBuilder();
        Disposable disposable = chatClient.prompt()
                .options(GoogleGenAiChatOptions.builder().responseMimeType("text/plain").build())
                .system(context.systemPrompt()).user(context.userPrompt())
                .stream().content()
                .doOnNext(token -> {
                    generated.append(token);
                    sendEvent(emitter, "delta", new RepositoryChatDtos.Delta(token));
                })
                .doOnError(error -> {
                    log.warn("Gemini repository chat failed ingestionId={} error={}", ingestion.getId(), error.getMessage());
                    sendError(emitter, "Gemini could not finish the answer. Check your API quota or retry shortly.");
                })
                .doOnComplete(() -> finishAnswer(ingestion, context, generated.toString(), emitter, started))
                .subscribe();
        subscription.set(disposable);
    }

    private void finishAnswer(IngestionJobEntity ingestion, RepositoryChatContextBuilder.BuiltContext context,
                              String generated, SseEmitter emitter, long started) {
        String answer = RepositoryChatContextBuilder.sanitizeAnswer(generated);
        if (!StringUtils.hasText(answer)) answer = INSUFFICIENT_EVIDENCE;
        sendEvent(emitter, "final", new RepositoryChatDtos.FinalAnswer(answer));
        log.info("Repository chat completed ingestionId={} answerLength={} durationMs={}",
                ingestion.getId(), answer.length(), elapsed(started));
        emitter.complete();
    }

    private static Integer positive(int value) { return value > 0 ? value : null; }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }

    private static void sendError(SseEmitter emitter, String message) {
        sendEvent(emitter, "error", new RepositoryChatDtos.StreamError(message));
        emitter.complete();
    }

    private static void sendEvent(SseEmitter emitter, String name, Object data) {
        try { emitter.send(SseEmitter.event().name(name).data(data)); }
        catch (IOException | IllegalStateException ignored) { }
    }
}
