package com.campuscode.service;

import com.campuscode.api.EvaluationDtos;
import com.campuscode.persistence.EvaluationJobEntity;
import com.campuscode.persistence.EvaluationJobRepository;
import com.campuscode.persistence.IngestionJobEntity;
import com.campuscode.persistence.IngestionJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

@Service
public class EvaluationService {
    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);
    private static final List<String> DIMENSIONS = List.of("architecture", "maintainability", "testing",
            "reliability", "security", "performance/scalability");

    private final RestClient cognee;
    private final ChatClient chatClient;
    private final IngestionJobRepository ingestions;
    private final EvaluationJobRepository evaluations;
    private final ObjectMapper mapper;
    private final ExecutorService worker;
    private final EvaluationStreamHub streamHub;
    private final JavaMailSender mailSender;
    private final String mailHost;
    private final String fromEmail;
    private final CogneeDatasetResolver datasetResolver;
    private final EvaluationContextBuilder contextBuilder;
    private final GitHubRepositoryMetadataClient githubMetadata;
    private final int contextTokenBudget;
    private final ResourceLoader resourceLoader;
    @Value("${campuscode.evaluation.retrieval.audit-content:false}")
    private boolean retrievalAuditContent;
    @Value("${campuscode.evaluation.retrieval.audit-preview-chars:500}")
    private int retrievalAuditPreviewChars;

    public EvaluationService(RestClient cognee, ChatClient.Builder chatClientBuilder,
                             IngestionJobRepository ingestions, EvaluationJobRepository evaluations,
                             ObjectMapper mapper, ExecutorService worker, EvaluationStreamHub streamHub,
                             JavaMailSender mailSender,
                             @Value("${spring.mail.host:}") String mailHost,
                             @Value("${campuscode.evaluation.from-email:}") String fromEmail,
                             @Value("${campuscode.evaluation.context.max-estimated-tokens:40000}") int contextTokenBudget,
                             @Value("${campuscode.evaluation.context.chars-per-token:3}") int charsPerToken,
                             @Value("${campuscode.evaluation.context.max-evidence-per-dimension:4}") int maxEvidencePerDimension,
                             @Value("${campuscode.evaluation.context.max-evidence-chars:4000}") int maxEvidenceChars,
                             @Value("${campuscode.evaluation.context.max-graph-facts:36}") int maxGraphFacts,
                             @Value("${campuscode.evaluation.context.max-graph-description-chars:500}") int maxGraphDescriptionChars,
                             ResourceLoader resourceLoader) {
        this.cognee = cognee;
        this.chatClient = chatClientBuilder.build();
        this.ingestions = ingestions;
        this.evaluations = evaluations;
        this.mapper = mapper;
        this.worker = worker;
        this.streamHub = streamHub;
        this.mailSender = mailSender;
        this.mailHost = mailHost;
        this.fromEmail = fromEmail;
        this.datasetResolver = new CogneeDatasetResolver(cognee);
        this.contextTokenBudget = contextTokenBudget;
        this.contextBuilder = new EvaluationContextBuilder(mapper, contextTokenBudget, charsPerToken,
                maxEvidencePerDimension, maxEvidenceChars, maxGraphFacts, maxGraphDescriptionChars);
        this.githubMetadata = new GitHubRepositoryMetadataClient(mapper);
        this.resourceLoader = resourceLoader;
    }

    public synchronized EvaluationDtos.EvaluationStatus start(UUID ingestionId) {
        Prepared prepared = prepare(ingestionId);
        if (prepared.launch()) schedule(prepared, null);
        return status(prepared.evaluation());
    }

    public synchronized SseEmitter stream(UUID ingestionId) {
        Prepared prepared = prepare(ingestionId);
        EvaluationJobEntity job = prepared.evaluation();
        SseEmitter emitter = streamHub.subscribe(job.getId());

        if (job.getStatus() == EvaluationJobEntity.State.COMPLETED) {
            streamHub.sendCompleted(emitter, report(job));
            return emitter;
        }
        if (job.getStatus() == EvaluationJobEntity.State.FAILED) {
            streamHub.sendFailed(emitter, status(job));
            return emitter;
        }

        streamHub.sendSnapshot(emitter, progress(job, "Evaluation is in progress."));
        if (prepared.launch()) schedule(prepared, emitter);
        return emitter;
    }

    public EvaluationDtos.EvaluationStatus getStatus(UUID id) {
        return status(evaluations.findById(id).orElseThrow(() -> new IllegalArgumentException("Evaluation not found: " + id)));
    }

    public EvaluationDtos.EvaluationContextPreview previewContext(UUID ingestionId) {
        IngestionJobEntity ingestion = ingestions.findById(ingestionId)
                .orElseThrow(() -> new IllegalArgumentException("Ingestion not found: " + ingestionId));
        if (ingestion.getStatus() != com.campuscode.api.IngestionStatus.State.COMPLETED) {
            throw new IllegalStateException("Repository ingestion must be COMPLETED before context preview");
        }
        RetrievedEvidence retrieved = retrieveEvidence(null, ingestion);
        EvaluationDtos.RepositoryArchitecture architecture = (EvaluationDtos.RepositoryArchitecture) retrieveArchitectureAnalysis(ingestionId, ingestion, resolveDatasetId(ingestion));
        EvaluationDtos.EvidenceBundle unbounded = toPreviewEvidenceBundle(ingestion, retrieved);
        EvaluationContextBuilder.BuiltContext bounded = contextBuilder.build(
                unbounded, retrieved.evidence(), systemPrompt(), ingestion.getRepositoryUrl());
        Map<String, Integer> discarded = new LinkedHashMap<>();
        retrieved.searches().forEach(search -> search.discardedByReason().forEach((reason, count) ->
                discarded.merge(reason, count, Integer::sum)));
        int source = (int) bounded.bundle().evidence().stream()
                .filter(item -> "SOURCE_CODE".equals(item.evidenceKind())).count();
        int tests = (int) bounded.bundle().evidence().stream()
                .filter(item -> "TEST_CODE".equals(item.evidenceKind())).count();
        int docs = (int) bounded.bundle().evidence().stream()
                .filter(item -> "SUPPORTING_DOCUMENTATION".equals(item.evidenceKind())).count();
        EvaluationDtos.ContextDiagnostics diagnostics = new EvaluationDtos.ContextDiagnostics(
                bounded.estimatedTokens(), contextTokenBudget, retrieved.candidateCount(), retrieved.citationReadyCount(),
                bounded.bundle().evidence().size(), source, tests, docs, bounded.bundle().graphContext().size(),
                bounded.bundle().graphEdges().size(), bounded.discardedMissingId(), bounded.discardedMissingSource(),
                bounded.discardedMissingPath() + discarded.getOrDefault("MISSING_REPOSITORY_PATH", 0)
                        + discarded.getOrDefault("OFF_REPOSITORY_PATH", 0),
                bounded.discardedInvalidLines() + discarded.getOrDefault("MISSING_OR_INVALID_LINE_RANGE", 0),
                Map.copyOf(discarded), retrieved.searches());
        log.info("Cognee evaluation context preview completed ingestionId={} datasetId={} candidates={} citationReady={} retainedPassages={} graphFacts={} graphEdges={} estimatedTokens={} budget={}",
                ingestionId, ingestion.getCogneeDatasetId(), retrieved.candidateCount(), retrieved.citationReadyCount(),
                bounded.bundle().evidence().size(), bounded.bundle().graphContext().size(),
                bounded.bundle().graphEdges().size(), bounded.estimatedTokens(), contextTokenBudget);
        return new EvaluationDtos.EvaluationContextPreview(ingestionId, ingestion.getRepositoryUrl(),
                ingestion.getCogneeDatasetId(),
                "Paths and line ranges are Cognee-provided metadata and have not been verified against a source checkout.",
                bounded.bundle(), diagnostics, architecture,
                "Cognee code-graph architecture analysis. File paths and line numbers are graph metadata, not checkout-verified.");
    }

    public EvaluationDtos.CogneeRepositoryAnalysisPreview previewCogneeRepositoryAnalysis(UUID ingestionId) {
        IngestionJobEntity ingestion = ingestions.findById(ingestionId)
                .orElseThrow(() -> new IllegalArgumentException("Ingestion not found: " + ingestionId));
        if (ingestion.getStatus() != com.campuscode.api.IngestionStatus.State.COMPLETED) {
            throw new IllegalStateException("Repository ingestion must be COMPLETED before analysis preview");
        }
        String datasetId = resolveDatasetId(ingestion);
        Object response = retrieveArchitectureAnalysis(ingestionId, ingestion, datasetId);
        return new EvaluationDtos.CogneeRepositoryAnalysisPreview(ingestionId, ingestion.getRepositoryUrl(),
                datasetId, "architecture", response,
                "Cognee code-graph architecture analysis. File paths and line numbers are graph metadata, not checkout-verified; this preview does not generate an evaluation or call Gemini.");
    }

    private String resolveDatasetId(IngestionJobEntity ingestion) {
        String datasetId = ingestion.getCogneeDatasetId();
        if (!StringUtils.hasText(datasetId)) {
            datasetId = datasetResolver.resolveId(ingestion.getDatasetName());
            ingestion.setCogneeDatasetId(datasetId);
            ingestions.save(ingestion);
        }
        return datasetId;
    }

    private Object retrieveArchitectureAnalysis(UUID ingestionId, IngestionJobEntity ingestion, String datasetId) {
        log.info("Cognee architecture analysis preview started ingestionId={} datasetId={}", ingestionId, datasetId);
        Object rawResponse = cognee.post().uri("/api/v1/search")
                .body(Map.of("query", "architecture", "dataset_ids", List.of(datasetId),
                        "search_type", "CODE", "code_query", Map.of("operation", "architecture"),
                        "top_k", 8, "verbose", true, "only_context", true))
                .retrieve().body(Object.class);
        JsonNode response = rawResponse instanceof JsonNode ? (JsonNode) rawResponse : mapper.valueToTree(rawResponse);
        log.info("Cognee architecture analysis preview completed ingestionId={} repository={}", ingestionId,
                ingestion.getRepositoryUrl());
        JsonNode root = response != null && response.isArray() && !response.isEmpty() ? response.get(0) : response;
        JsonNode result = root == null ? mapper.createObjectNode() : root.path("context_result");
        if (result.isTextual()) try { result = mapper.readTree(result.asText()); } catch (Exception ignored) { result = mapper.createObjectNode(); }
        JsonNode nodes = result.path("nodes"), edges = result.path("edges"), stats = result.path("stats");
        List<EvaluationDtos.ArchitectureNode> compactNodes = new ArrayList<>();
        if (nodes.isArray()) nodes.forEach(n -> compactNodes.add(new EvaluationDtos.ArchitectureNode(n.path("id").asText(null), n.path("kind").asText(null), n.path("name").asText(null), n.path("file").asText(null), n.has("line") ? n.path("line").asInt() : null, limit(n.path("description").asText(""), 240))));
        List<EvaluationDtos.ArchitectureRelation> compactEdges = new ArrayList<>();
        if (edges.isArray()) edges.forEach(e -> compactEdges.add(new EvaluationDtos.ArchitectureRelation(e.path("source").asText(null), e.path("type").asText(null), e.path("target").asText(null), e.has("count") ? e.path("count").asInt() : 1)));
        return new EvaluationDtos.RepositoryArchitecture("Cognee graph metadata; paths and lines are not checkout-verified.", Map.of("nodes", stats.path("nodes_total").asInt(compactNodes.size()), "edges", stats.path("edges_shown").asInt(compactEdges.size()), "truncated", stats.path("truncated").asBoolean(false) ? 1 : 0), List.copyOf(compactNodes), List.copyOf(compactEdges));
    }

    public EvaluationDtos.EvaluationReport getReportForIngestion(UUID ingestionId) {
        EvaluationJobEntity job = evaluations.findByIngestionId(ingestionId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation not found for ingestion: " + ingestionId));
        if (job.getStatus() != EvaluationJobEntity.State.COMPLETED || job.getReportJson() == null) {
            throw new IllegalStateException("Evaluation report is not ready; current status is " + job.getStatus());
        }
        return report(job);
    }

    public EvaluationDtos.EvidenceBundle getEvidenceForIngestion(UUID ingestionId) {
        EvaluationJobEntity job = evaluations.findByIngestionId(ingestionId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation not found for ingestion: " + ingestionId));
        if (job.getEvidenceJson() == null) throw new IllegalStateException("Evaluation evidence is not ready; current status is " + job.getStatus());
        try { return mapper.readValue(job.getEvidenceJson(), EvaluationDtos.EvidenceBundle.class); }
        catch (Exception e) { throw new IllegalStateException("Stored evaluation evidence could not be read", e); }
    }

    public EvaluationDtos.GraphContextGraph getGraphContextForIngestion(UUID ingestionId) {
        EvaluationJobEntity job = evaluations.findByIngestionId(ingestionId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation not found for ingestion: " + ingestionId));
        if (job.getStatus() != EvaluationJobEntity.State.COMPLETED || job.getEvidenceJson() == null) {
            throw new IllegalStateException("Completed evaluation evidence is not available for graph display");
        }
        EvaluationDtos.EvidenceBundle bundle;
        try { bundle = mapper.readValue(job.getEvidenceJson(), EvaluationDtos.EvidenceBundle.class); }
        catch (Exception e) { throw new IllegalStateException("Stored evaluation graph context could not be read", e); }

        Map<String, EvaluationDtos.GraphFact> nodes = new LinkedHashMap<>();
        if (bundle.graphContext() != null) bundle.graphContext().stream()
                .filter(fact -> fact != null && StringUtils.hasText(fact.id()))
                .forEach(fact -> nodes.putIfAbsent(fact.id(), fact));
        List<EvaluationDtos.GraphEdge> storedEdges = bundle.graphEdges();
        if (storedEdges != null && !storedEdges.isEmpty()) {
            List<EvaluationDtos.GraphEdge> edges = storedEdges.stream().filter(edge -> edge != null
                    && nodes.containsKey(edge.sourceId()) && nodes.containsKey(edge.targetId())).toList();
            return new EvaluationDtos.GraphContextGraph(job.getId(), ingestionId, job.getRepositoryUrl(),
                    false, List.copyOf(nodes.values()), edges);
        }

        // Older evidence bundles persisted node relationships as labels but not edge endpoint IDs.
        // Reconstruct those edges from this ingestion's current, dataset-scoped Cognee graph.
        IngestionJobEntity ingestion = ingestions.findById(ingestionId)
                .orElseThrow(() -> new IllegalArgumentException("Ingestion not found: " + ingestionId));
        String datasetId = ingestion.getCogneeDatasetId();
        if (!StringUtils.hasText(datasetId)) {
            datasetId = datasetResolver.resolveId(ingestion.getDatasetName());
            ingestion.setCogneeDatasetId(datasetId);
            ingestions.save(ingestion);
        }
        JsonNode currentGraph = cognee.get().uri("/api/v1/datasets/{datasetId}/graph", datasetId)
                .retrieve().body(JsonNode.class);
        Map<String, String> currentLabels = new LinkedHashMap<>();
        if (currentGraph != null && currentGraph.path("nodes").isArray()) currentGraph.path("nodes").forEach(node -> {
            String id = node.path("id").asText(null);
            if (id != null) currentLabels.put(id, node.path("label").asText(""));
        });
        List<EvaluationDtos.GraphEdge> edges = new ArrayList<>();
        if (currentGraph != null && currentGraph.path("edges").isArray()) currentGraph.path("edges").forEach(edge -> {
            String sourceId = edge.path("source").asText(null);
            String targetId = edge.path("target").asText(null);
            String label = edge.path("label").asText(null);
            EvaluationDtos.GraphFact source = nodes.get(sourceId);
            EvaluationDtos.GraphFact target = nodes.get(targetId);
            if (source == null || target == null || !StringUtils.hasText(label)) return;
            String outgoing = label + " → " + currentLabels.getOrDefault(targetId, "");
            String incoming = currentLabels.getOrDefault(sourceId, "") + " → " + label;
            if (!containsRelationship(source, outgoing) && !containsRelationship(target, incoming)) return;
            Set<String> dimensions = new java.util.LinkedHashSet<>();
            if (source.dimensions() != null) dimensions.addAll(source.dimensions());
            if (target.dimensions() != null) dimensions.addAll(target.dimensions());
            edges.add(new EvaluationDtos.GraphEdge(sourceId, targetId, label, List.copyOf(dimensions)));
        });
        log.info("Report graph context reconstructed from current Cognee graph ingestionId={} datasetId={} nodeCount={} edgeCount={}",
                ingestionId, datasetId, nodes.size(), edges.size());
        return new EvaluationDtos.GraphContextGraph(job.getId(), ingestionId, job.getRepositoryUrl(),
                true, List.copyOf(nodes.values()), List.copyOf(edges));
    }

    private static boolean containsRelationship(EvaluationDtos.GraphFact fact, String expected) {
        return fact.relationships() != null && fact.relationships().contains(expected);
    }

    private Prepared prepare(UUID ingestionId) {
        IngestionJobEntity ingestion = ingestions.findById(ingestionId)
                .orElseThrow(() -> new IllegalArgumentException("Ingestion not found: " + ingestionId));
        if (ingestion.getStatus() != com.campuscode.api.IngestionStatus.State.COMPLETED) {
            throw new IllegalStateException("Repository ingestion must be COMPLETED before evaluation");
        }
        EvaluationJobEntity existing = evaluations.findByIngestionId(ingestionId).orElse(null);
        if (existing != null && existing.getStatus() != EvaluationJobEntity.State.FAILED) {
            return new Prepared(ingestion, existing, false);
        }

        EvaluationJobEntity job;
        if (existing == null) {
            job = new EvaluationJobEntity(UUID.randomUUID(), ingestionId, ingestion.getRepositoryUrl());
        } else {
            job = existing;
            job.transition(EvaluationJobEntity.State.QUEUED, null, null);
        }
        evaluations.save(job);
        log.info("Evaluation accepted evaluationId={} ingestionId={} repository={} status={}",
                job.getId(), ingestionId, ingestion.getRepositoryUrl(), job.getStatus());
        return new Prepared(ingestion, job, true);
    }

    private void schedule(Prepared prepared, SseEmitter emitter) {
        try {
            worker.submit(() -> run(prepared.evaluation().getId(), prepared.ingestion().getId()));
        } catch (RuntimeException e) {
            EvaluationJobEntity job = prepared.evaluation();
            job.transition(EvaluationJobEntity.State.FAILED, null, "Unable to schedule evaluation worker");
            evaluations.save(job);
            if (emitter != null) streamHub.sendFailed(emitter, status(job));
            else throw e;
        }
    }

    private EvaluationDtos.EvaluationReport report(EvaluationJobEntity job) {
        try { return mapper.readValue(job.getReportJson(), EvaluationDtos.EvaluationReport.class); }
        catch (Exception e) { throw new IllegalStateException("Stored evaluation report could not be read", e); }
    }

    private static EvaluationDtos.EvaluationProgress progress(EvaluationJobEntity job, String message) {
        return new EvaluationDtos.EvaluationProgress(job.getId(), job.getIngestionId(), job.getStatus(),
                job.getStage(), job.getProgress(), message, job.getUpdatedAt());
    }

    private record Prepared(IngestionJobEntity ingestion, EvaluationJobEntity evaluation, boolean launch) { }

    private void run(UUID evaluationId, UUID ingestionId) {
        long started = System.nanoTime();
        EvaluationJobEntity job = evaluations.findById(evaluationId).orElseThrow();
        IngestionJobEntity ingestion = ingestions.findById(ingestionId).orElseThrow();
        try {
            job.transition(EvaluationJobEntity.State.EVALUATING, null, null);
            job.updateProgress("retrieving_evidence", 5);
            evaluations.save(job);
            publishProgress(job, "Retrieving repository evidence.");
            log.info("Evaluation started evaluationId={} ingestionId={} repository={} dataset={}",
                    evaluationId, ingestionId, ingestion.getRepositoryUrl(), ingestion.getDatasetName());

            RetrievedEvidence retrieved = retrieveEvidence(job, ingestion);
            EvaluationDtos.RepositoryArchitecture architecture = (EvaluationDtos.RepositoryArchitecture) retrieveArchitectureAnalysis(ingestionId, ingestion, resolveDatasetId(ingestion));
            List<RepositoryEvidence> evidence = retrieved.evidence();
            log.info("Evaluation evidence retrieved evaluationId={} ingestionId={} evidenceCount={}",
                    evaluationId, ingestionId, evidence.size());
            EvaluationContextBuilder.BuiltContext context = contextBuilder.build(
                    toGraphEvidenceBundle(job, ingestion, retrieved, architecture), List.of(), systemPrompt(), ingestion.getRepositoryUrl());
            String evidenceJson = context.json();
            job.setEvidenceJson(evidenceJson);
            evaluations.save(job);
            log.info("Evaluation context bounded evaluationId={} estimatedTokens={} budget={} retrievedItems={} sourceCodePassages={} documentationPassages={} otherPassages={} graphFacts={} scoreableGraphSymbols={} omittedPassages={} omittedGraphFacts={} discardedMissingId={} discardedMissingSource={} discardedMissingPath={} discardedInvalidLines={} perDimension={} graphSymbolsPerDimension={} truncated={}",
                    evaluationId, context.estimatedTokens(), contextTokenBudget, context.retrievedEvidenceItems(),
                    context.sourceCodePassages(), context.documentationPassages(),
                    context.otherPassages(), context.bundle().graphContext().size(), context.scoreableGraphSymbols(),
                    context.omittedEvidence(), context.omittedGraphFacts(), context.discardedMissingId(), context.discardedMissingSource(),
                    context.discardedMissingPath(), context.discardedInvalidLines(), context.evidencePerDimension(),
                    context.graphSymbolsPerDimension(), context.truncated());
            if (context.scoreableGraphSymbols() == 0) {
                throw new IllegalStateException("Cognee returned no repository-scoped code or test graph symbols; inspect the evidence bundle and graph metadata");
            }
            setProgress(job, "generating_scorecard", 68, "Generating the engineering scorecard.");
            EvaluationDtos.ModelScorecard scorecard = createScorecard(ingestion, evidenceJson);
            setProgress(job, "validating_citations", 85, "Validating scorecard evidence and citations.");
            EvaluationDtos.EvaluationReport report = validateAndBuild(job, ingestion, scorecard,
                    context.bundle().graphContext(), githubMetadata.defaultBranch(ingestion.getRepositoryUrl()));
            setProgress(job, "saving_report", 95, "Saving the completed report.");
            String json = mapper.writeValueAsString(report);
            job.transition(EvaluationJobEntity.State.COMPLETED, json, null);
            evaluations.save(job);
            streamHub.publishCompleted(evaluationId, report);
            log.info("Evaluation completed evaluationId={} ingestionId={} overallScore={} durationMs={}",
                    evaluationId, ingestionId, report.overallScore(), elapsed(started));
            sendNotification(ingestion, report);
        } catch (Exception e) {
            String message = safeMessage(e);
            job.transition(EvaluationJobEntity.State.FAILED, null, message);
            evaluations.save(job);
            streamHub.publishFailed(evaluationId, status(job));
            log.error("Evaluation failed evaluationId={} ingestionId={} durationMs={} error={}",
                    evaluationId, ingestionId, elapsed(started), message, e);
        }
    }

    private RetrievedEvidence retrieveEvidence(EvaluationJobEntity job, IngestionJobEntity ingestion) {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("architecture", "Source code showing application entry points, module boundaries, component responsibilities, and dependencies.");
        queries.put("maintainability", "Source code showing naming, separation of concerns, duplication, complexity, and maintainable abstractions.");
        queries.put("testing", "Automated tests and test configuration demonstrating behavior and edge-case coverage.");
        queries.put("reliability", "Error handling, validation, resilience, resource management, and operational configuration in source code.");
        queries.put("security", "Authentication, authorization, input validation, secret handling, cryptography, and security-sensitive code.");
        queries.put("performance/scalability", "Algorithms, data access, concurrency, caching, resource usage, and scalability-related source code.");
        String datasetId = ingestion.getCogneeDatasetId();
        if (!StringUtils.hasText(datasetId)) {
            datasetId = datasetResolver.resolveId(ingestion.getDatasetName());
            ingestion.setCogneeDatasetId(datasetId);
            ingestions.save(ingestion);
        }
        JsonNode graph = cognee.get().uri("/api/v1/datasets/{datasetId}/graph", datasetId)
                .retrieve().body(JsonNode.class);
        Map<String, RepositoryEvidence> unique = new LinkedHashMap<>();
        Map<String, Set<String>> dimensionsByEvidenceId = new LinkedHashMap<>();
        List<EvaluationDtos.SearchResultDiagnostic> searchDiagnostics = new ArrayList<>();
        List<EvaluationDtos.GraphFact> graphFacts = new ArrayList<>();
        List<EvaluationDtos.GraphEdge> graphEdges = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, String> dimensionQuery : queries.entrySet()) {
            if (job != null) setProgress(job, "retrieving_evidence", 8 + index * 8,
                    "Retrieving " + dimensionQuery.getKey() + " evidence.");
            log.info("Cognee evidence retrieval started ingestionId={} datasetId={} dimension={}",
                    ingestion.getId(), datasetId, dimensionQuery.getKey());
            int graphFactCountBefore = graphFacts.size();
            Object codeResponse = null;
            try {
                Map<String, Object> codeRequest = Map.of("query", dimensionQuery.getValue(), "dataset_ids", List.of(datasetId),
                        "search_type", "CODE", "code_query", Map.of("operation", "ask"), "top_k", 8,
                        "verbose", true, "only_context", true);
                codeResponse = cognee.post().uri("/api/v1/search").body(codeRequest).retrieve().body(Object.class);
            } catch (HttpClientErrorException.UnprocessableEntity e) {
                log.warn("Cognee CODE search unavailable; using graph neighborhood and chunk retrieval ingestionId={} dimension={} status={}",
                        ingestion.getId(), dimensionQuery.getKey(), e.getStatusCode().value());
                searchDiagnostics.add(new EvaluationDtos.SearchResultDiagnostic(dimensionQuery.getKey(), "CODE",
                        0, 0, 0, Map.of(), List.of(), List.of(), 0, 0, 0, "HTTP_422"));
            }
            Map<String, Object> chunkRequest = Map.of("query", dimensionQuery.getValue(), "dataset_ids", List.of(datasetId),
                    "search_type", "CHUNKS", "top_k", 8, "only_context", true, "verbose", true);
            Object chunkResponse = cognee.post().uri("/api/v1/search").body(chunkRequest).retrieve().body(Object.class);
            RepositoryEvidence.Extraction chunkExtraction = RepositoryEvidence.extractWithAudit(
                    chunkResponse, mapper, ingestion.getRepositoryUrl());
            auditCogneeResponse(ingestion, dimensionQuery.getKey(), "CHUNKS", chunkResponse, chunkExtraction);
            searchDiagnostics.add(searchDiagnostic(dimensionQuery.getKey(), "CHUNKS", chunkExtraction));
            List<RepositoryEvidence> sourceItems = new ArrayList<>(chunkExtraction.evidence());
            RepositoryEvidence.CodeSearchSummary codeSummary = new RepositoryEvidence.CodeSearchSummary(
                    List.of(), List.of(), 0, 0);
            if (codeResponse != null) {
                codeSummary = RepositoryEvidence.summarizeCodeSearch(codeResponse, mapper);
                searchDiagnostics.add(new EvaluationDtos.SearchResultDiagnostic(dimensionQuery.getKey(), "CODE",
                        0, 0, 0, Map.of(), codeSummary.resultPaths(), List.of(), codeSummary.seedNodeIds().size(),
                        codeSummary.neighborhoodNodeCount(), codeSummary.neighborhoodEdgeCount(), null));
                log.info("Cognee CODE graph result parsed ingestionId={} dimension={} seeds={} neighborhoodNodes={} neighborhoodEdges={}",
                        ingestion.getId(), dimensionQuery.getKey(), codeSummary.seedNodeIds().size(),
                        codeSummary.neighborhoodNodeCount(), codeSummary.neighborhoodEdgeCount());
            }
            List<RepositoryEvidence> dimensionEvidence = new ArrayList<>();
            for (RepositoryEvidence item : sourceItems) {
                if (!StringUtils.hasText(item.filePath())) continue;
                String key = item.filePath().replace('\\', '/').toLowerCase(java.util.Locale.ROOT).strip()
                        + "\n" + item.text().strip();
                RepositoryEvidence stored = unique.get(key);
                if (stored == null) {
                    stored = new RepositoryEvidence("e" + (unique.size() + 1), item.filePath(), item.text(), item.startLine(), item.endLine());
                    unique.put(key, stored);
                } else if (stored.startLine() <= 0 && item.startLine() > 0) {
                    stored = new RepositoryEvidence(stored.id(), stored.filePath(), stored.text(), item.startLine(), item.endLine());
                    unique.put(key, stored);
                }
                dimensionEvidence.add(stored);
                dimensionsByEvidenceId.computeIfAbsent(stored.id(), ignored -> new java.util.LinkedHashSet<>())
                        .add(dimensionQuery.getKey());
            }
            RepositoryGraphExpander.Expansion expansion = RepositoryGraphExpander.expandDetailed(graph,
                    dimensionQuery.getKey(), dimensionQuery.getValue(), null, chunkResponse,
                    ingestion.getRepositoryUrl(), Set.copyOf(codeSummary.seedNodeIds()));
            graphFacts.addAll(expansion.facts());
            graphEdges.addAll(expansion.edges());
            log.info("Cognee evidence retrieval completed ingestionId={} dimension={} chunkCount={} graphFactCount={}",
                    ingestion.getId(), dimensionQuery.getKey(), dimensionEvidence.size(), graphFacts.size() - graphFactCountBefore);
            index++;
        }
        List<RepositoryEvidence> evidence = List.copyOf(unique.values());
        int candidates = searchDiagnostics.stream().mapToInt(EvaluationDtos.SearchResultDiagnostic::candidateCount).sum();
        int citationReady = searchDiagnostics.stream().mapToInt(EvaluationDtos.SearchResultDiagnostic::citationReadyCount).sum();
        return new RetrievedEvidence(evidence, dimensionsByEvidenceId, List.copyOf(graphFacts),
                List.copyOf(graphEdges), List.copyOf(searchDiagnostics), candidates, citationReady);
    }

    private static EvaluationDtos.SearchResultDiagnostic searchDiagnostic(String dimension, String type,
                                                                           RepositoryEvidence.Extraction extraction) {
        Map<String, Integer> discarded = new LinkedHashMap<>();
        List<String> paths = new ArrayList<>();
        List<EvaluationDtos.RawSearchResult> results = new ArrayList<>();
        int usablePaths = 0;
        for (RepositoryEvidence.RawCandidate candidate : extraction.candidates()) {
            if (StringUtils.hasText(candidate.filePath())) usablePaths++;
            if (StringUtils.hasText(candidate.filePath()) && !paths.contains(candidate.filePath())) paths.add(candidate.filePath());
            if (candidate.discardReason() != null) discarded.merge(candidate.discardReason(), 1, Integer::sum);
            String content = candidate.content() == null ? "" : candidate.content();
            boolean truncated = content.length() > 2000;
            if (truncated) content = content.substring(0, 2000) + "…[preview truncated]";
            results.add(new EvaluationDtos.RawSearchResult(candidate.originalPath(), candidate.filePath(),
                    candidate.startLine() > 0 ? candidate.startLine() : null,
                    candidate.endLine() > 0 ? candidate.endLine() : null, content, truncated, candidate.discardReason()));
        }
        int citationReady = (int) extraction.candidates().stream()
                .filter(candidate -> candidate.discardReason() == null).count();
        return new EvaluationDtos.SearchResultDiagnostic(dimension, type, extraction.candidates().size(),
                usablePaths, citationReady, Map.copyOf(discarded), List.copyOf(paths), List.copyOf(results),
                0, 0, 0, null);
    }

    private EvaluationDtos.ModelScorecard createScorecard(IngestionJobEntity ingestion, String evidenceJson) {
        String system = systemPrompt();
        String user = "Repository: " + ingestion.getRepositoryUrl() + "\nEvidence JSON:\n" + evidenceJson;
        EvaluationDtos.ModelScorecard result = chatClient.prompt().system(system).user(user).call().entity(EvaluationDtos.ModelScorecard.class);
        if (result == null || result.dimensions() == null) throw new IllegalStateException("Gemini returned an empty scorecard");
        return result;
    }

    private void auditCogneeResponse(IngestionJobEntity ingestion, String dimension, String searchType,
                                     Object response, RepositoryEvidence.Extraction extraction) {
        log.info("Cognee retrieval audit ingestionId={} dimension={} searchType={} candidateCount={} usablePathCount={} citationReadyCount={}",
                ingestion.getId(), dimension, searchType, extraction.candidates().size(), extraction.evidence().size(),
                extraction.candidates().stream().filter(candidate -> candidate.discardReason() == null).count());
        int index = 0;
        for (RepositoryEvidence.RawCandidate candidate : extraction.candidates()) {
            log.info("Cognee result audit ingestionId={} dimension={} searchType={} resultIndex={} path={} normalizedPath={} startLine={} endLine={} contentChars={} citationDiscardReason={}",
                    ingestion.getId(), dimension, searchType, index++, candidate.originalPath(), candidate.filePath(), candidate.startLine(),
                    candidate.endLine(), candidate.content() == null ? 0 : candidate.content().length(), candidate.discardReason());
            if (retrievalAuditContent) {
                log.debug("Cognee result content preview ingestionId={} dimension={} searchType={} path={} preview={}",
                        ingestion.getId(), dimension, searchType, candidate.originalPath(),
                        candidate.preview(Math.max(0, retrievalAuditPreviewChars)));
            }
        }
        if (retrievalAuditContent) {
            try {
                String payload = mapper.writeValueAsString(response);
                int max = Math.max(1000, retrievalAuditPreviewChars * 24);
                log.debug("Cognee raw response ingestionId={} dimension={} searchType={} payload={}", ingestion.getId(),
                        dimension, searchType, payload.length() <= max ? payload : payload.substring(0, max) + "…[truncated]");
            } catch (Exception e) {
                log.debug("Could not serialize raw Cognee response for audit ingestionId={} dimension={} searchType={}",
                        ingestion.getId(), dimension, searchType, e);
            }
        }
    }

    private String systemPrompt() {
        try {
            return resourceLoader.getResource("classpath:system-prompt.st").getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to load system prompt from system-prompt.st", e);
            throw new IllegalStateException("Could not load system prompt from system-prompt.st", e);
        }
    }

    EvaluationDtos.EvaluationReport validateAndBuild(EvaluationJobEntity job, IngestionJobEntity ingestion,
                                                               EvaluationDtos.ModelScorecard scorecard,
                                                               List<EvaluationDtos.GraphFact> graphFacts,
                                                               String defaultBranch) {
        Map<String, EvaluationDtos.GraphFact> byId = graphFacts.stream().filter(fact -> fact.scoreable()
                        && StringUtils.hasText(fact.id()) && StringUtils.hasText(fact.filePath()))
                .collect(java.util.stream.Collectors.toMap(EvaluationDtos.GraphFact::id, item -> item,
                        (first, ignored) -> first));
        Map<String, EvaluationDtos.ModelDimensionScore> byName = scorecard.dimensions().stream()
                .collect(java.util.stream.Collectors.toMap(item -> normalize(item.dimension()), item -> item,
                        (first, ignored) -> first));
        List<EvaluationDtos.DimensionScore> normalized = new ArrayList<>();
        for (String dimension : DIMENSIONS) {
            EvaluationDtos.ModelDimensionScore source = byName.get(dimension);
            if (source == null || source.score() < 0 || source.score() > 5) {
                normalized.add(insufficient(dimension));
                continue;
            }
            if (Set.of("reliability", "security", "performance/scalability").contains(dimension)) {
                normalized.add(insufficient(dimension));
                continue;
            }
            List<String> ids = source.evidenceIds() == null ? List.of() : source.evidenceIds().stream().distinct()
                    .filter(id -> byId.containsKey(id) && byId.get(id).dimensions() != null
                            && byId.get(id).dimensions().contains(dimension)).toList();
            List<EvaluationDtos.GraphFact> supported = ids.stream().map(byId::get).toList();
            if (dimension.equals("testing") && supported.stream().noneMatch(EvaluationService::testSymbol)) {
                normalized.add(insufficient(dimension));
                continue;
            }
            if (dimension.equals("architecture") && (supported.size() < 2
                    || supported.stream().noneMatch(fact -> fact.relationships() != null && !fact.relationships().isEmpty()))) {
                normalized.add(insufficient(dimension));
                continue;
            }
            if (dimension.equals("maintainability") && (supported.stream().map(EvaluationDtos.GraphFact::filePath).distinct().count() < 2
                    || supported.stream().noneMatch(fact -> fact.relationships() != null && !fact.relationships().isEmpty()))) {
                normalized.add(insufficient(dimension));
                continue;
            }
            List<EvaluationDtos.Citation> citations = supported.stream().map(fact -> new EvaluationDtos.Citation(
                    fact.id(), fact.filePath(), fact.line(), fact.line(), null, fact.symbolName(), fact.symbolType(),
                    "COGNEE_GRAPH_METADATA")).toList();
            if (citations.isEmpty()) {
                normalized.add(insufficient(dimension));
            } else {
                Set<String> citedIds = citations.stream().map(EvaluationDtos.Citation::evidenceId).collect(java.util.stream.Collectors.toSet());
                List<EvaluationDtos.EvidencePoint> strengths = validatePoints(source.strengths(), citedIds);
                List<EvaluationDtos.EvidencePoint> improvements = validatePoints(source.areasForImprovement(), citedIds);
                normalized.add(new EvaluationDtos.DimensionScore(dimension, source.score(),
                        StringUtils.hasText(source.rationale()) ? source.rationale() : "Supported by Cognee graph structure; source behavior was not verified.",
                        strengths, improvements, citations));
            }
        }
        int total = normalized.stream().mapToInt(EvaluationDtos.DimensionScore::score).sum();
        int overall = Math.round(total * 100.0f / 30.0f);
        String summary = StringUtils.hasText(scorecard.summary()) ? scorecard.summary() : "Structural repository graph summary.";
        summary += " This report uses Cognee graph metadata only; symbol paths and optional line numbers were not verified against source file contents. A zero score is not a measure of a person's overall ability.";
        return new EvaluationDtos.EvaluationReport(job.getId(), ingestion.getId(), ingestion.getRepositoryUrl(),
                overall, summary, normalized, job.getCreatedAt(), java.time.Instant.now(), "GRAPH_METADATA", defaultBranch);
    }

    private static boolean testSymbol(EvaluationDtos.GraphFact fact) {
        String value = ((fact.filePath() == null ? "" : fact.filePath()) + " " +
                (fact.symbolName() == null ? "" : fact.symbolName())).toLowerCase(java.util.Locale.ROOT);
        return value.contains("/test/") || value.contains("/tests/") || value.matches(".*(?:test|spec).*" );
    }

    private static List<EvaluationDtos.EvidencePoint> validatePoints(List<EvaluationDtos.EvidencePoint> points, Set<String> citedIds) {
        if (points == null) return List.of();
        return points.stream().filter(point -> point != null && StringUtils.hasText(point.text())
                        && point.evidenceIds() != null && !point.evidenceIds().isEmpty()
                        && citedIds.containsAll(point.evidenceIds()))
                .map(point -> new EvaluationDtos.EvidencePoint(point.text().trim(), List.copyOf(point.evidenceIds())))
                .toList();
    }

    private EvaluationDtos.EvidenceBundle toGraphEvidenceBundle(EvaluationJobEntity job, IngestionJobEntity ingestion,
                                                                 RetrievedEvidence retrieved, EvaluationDtos.RepositoryArchitecture architecture) {
        // Graph-metadata citation mode intentionally excludes retrieved chunk bodies from Gemini's prompt.
        return new EvaluationDtos.EvidenceBundle(job.getId(), ingestion.getId(), ingestion.getRepositoryUrl(),
                List.of(), retrieved.graphFacts(), retrieved.graphEdges(), architecture);
    }

    private EvaluationDtos.EvidenceBundle toPreviewEvidenceBundle(IngestionJobEntity ingestion,
                                                                    RetrievedEvidence retrieved) {
        List<EvaluationDtos.RepositoryEvidenceItem> passages = retrieved.evidence().stream().map(item -> {
            List<String> dimensions = DIMENSIONS.stream().filter(retrieved.dimensionsByEvidenceId()
                    .getOrDefault(item.id(), Set.of())::contains).toList();
            String dimension = dimensions.isEmpty() ? DIMENSIONS.getFirst() : dimensions.getFirst();
            return new EvaluationDtos.RepositoryEvidenceItem(item.id(), dimension, item.filePath(), item.startLine(),
                    item.endLine(), item.text(), dimensions, null);
        }).toList();
        return new EvaluationDtos.EvidenceBundle(null, ingestion.getId(), ingestion.getRepositoryUrl(), passages,
                retrieved.graphFacts(), retrieved.graphEdges());
    }

    private record RetrievedEvidence(List<RepositoryEvidence> evidence,
                                    Map<String, Set<String>> dimensionsByEvidenceId,
                                    List<EvaluationDtos.GraphFact> graphFacts,
                                    List<EvaluationDtos.GraphEdge> graphEdges,
                                    List<EvaluationDtos.SearchResultDiagnostic> searches,
                                    int candidateCount, int citationReadyCount) { }

    private static String limit(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, max) + "…";
    }

    private static EvaluationDtos.DimensionScore insufficient(String dimension) {
        return new EvaluationDtos.DimensionScore(dimension, 0,
                "Insufficient verifiable repository evidence for this dimension.", List.of());
    }

    private static String normalize(String dimension) {
        if (dimension == null) return "";
        return dimension.trim().toLowerCase().replace('_', '/').replace("performance and scalability", "performance/scalability");
    }

    private void sendNotification(IngestionJobEntity ingestion, EvaluationDtos.EvaluationReport report) {
        String email = ingestion.getNotificationEmail();
        if (!StringUtils.hasText(email)) return;
        if (!StringUtils.hasText(mailHost) || !StringUtils.hasText(fromEmail)) {
            log.warn("Evaluation completed but email was not sent because SMTP is not configured ingestionId={} recipient={}",
                    ingestion.getId(), email);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(email);
            message.setSubject("CampusCode evaluation completed");
            message.setText("Your CampusCode repository evaluation is ready.\nRepository: " +
                    ingestion.getRepositoryUrl() + "\nOverall score: " + report.overallScore() + "/100\n" +
                    "Retrieve the report from CampusCode using ingestion ID " + ingestion.getId() + ".");
            mailSender.send(message);
            log.info("Evaluation notification sent ingestionId={} recipient={}", ingestion.getId(), email);
        } catch (Exception e) {
            log.error("Evaluation completed but notification failed ingestionId={} recipient={} error={}",
                    ingestion.getId(), email, safeMessage(e), e);
        }
    }

    private static EvaluationDtos.EvaluationStatus status(EvaluationJobEntity job) {
        return new EvaluationDtos.EvaluationStatus(job.getId(), job.getIngestionId(), job.getRepositoryUrl(),
                job.getStatus(), job.getCreatedAt(), job.getUpdatedAt(), job.getError());
    }

    private void setProgress(EvaluationJobEntity job, String stage, int percentage, String message) {
        job.updateProgress(stage, percentage);
        evaluations.save(job);
        publishProgress(job, message);
    }

    private void publishProgress(EvaluationJobEntity job, String message) {
        streamHub.publishProgress(job.getId(), progress(job, message));
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
