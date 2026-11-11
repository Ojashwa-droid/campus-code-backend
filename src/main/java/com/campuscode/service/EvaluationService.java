package com.campuscode.service;

import com.campuscode.api.EvaluationDtos;
import com.campuscode.persistence.EvaluationJobEntity;
import com.campuscode.persistence.EvaluationJobRepository;
import com.campuscode.persistence.IngestionJobEntity;
import com.campuscode.persistence.IngestionJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public EvaluationService(RestClient cognee, ChatClient.Builder chatClientBuilder,
                             IngestionJobRepository ingestions, EvaluationJobRepository evaluations,
                             ObjectMapper mapper, ExecutorService worker, EvaluationStreamHub streamHub,
                             JavaMailSender mailSender,
                             @Value("${spring.mail.host:}") String mailHost,
                             @Value("${campuscode.evaluation.from-email:}") String fromEmail) {
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

    public EvaluationDtos.EvaluationReport getReportForIngestion(UUID ingestionId) {
        EvaluationJobEntity job = evaluations.findByIngestionId(ingestionId)
                .orElseThrow(() -> new IllegalArgumentException("Evaluation not found for ingestion: " + ingestionId));
        if (job.getStatus() != EvaluationJobEntity.State.COMPLETED || job.getReportJson() == null) {
            throw new IllegalStateException("Evaluation report is not ready; current status is " + job.getStatus());
        }
        return report(job);
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

            List<RepositoryEvidence> evidence = retrieveEvidence(job, ingestion);
            log.info("Evaluation evidence retrieved evaluationId={} ingestionId={} evidenceCount={}",
                    evaluationId, ingestionId, evidence.size());
            setProgress(job, "generating_scorecard", 68, "Generating the engineering scorecard.");
            EvaluationDtos.Scorecard scorecard = createScorecard(ingestion, evidence);
            setProgress(job, "validating_citations", 85, "Validating scorecard evidence and citations.");
            EvaluationDtos.EvaluationReport report = validateAndBuild(job, ingestion, scorecard, evidence);
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

    private List<RepositoryEvidence> retrieveEvidence(EvaluationJobEntity job, IngestionJobEntity ingestion) {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("architecture", "Source code showing application entry points, module boundaries, component responsibilities, and dependencies.");
        queries.put("maintainability", "Source code showing naming, separation of concerns, duplication, complexity, and maintainable abstractions.");
        queries.put("testing", "Automated tests and test configuration demonstrating behavior and edge-case coverage.");
        queries.put("reliability", "Error handling, validation, resilience, resource management, and operational configuration in source code.");
        queries.put("security", "Authentication, authorization, input validation, secret handling, cryptography, and security-sensitive code.");
        queries.put("performance/scalability", "Algorithms, data access, concurrency, caching, resource usage, and scalability-related source code.");
        Map<String, RepositoryEvidence> unique = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, String> dimensionQuery : queries.entrySet()) {
            setProgress(job, "retrieving_evidence", 8 + index * 8,
                    "Retrieving " + dimensionQuery.getKey() + " evidence.");
            Map<String, Object> request = Map.of("query", dimensionQuery.getValue(),
                    "datasets", new String[]{ingestion.getDatasetName()}, "search_type", "CHUNKS", "top_k", 5);
            log.info("Cognee evidence retrieval started ingestionId={} dataset={} dimension={}",
                    ingestion.getId(), ingestion.getDatasetName(), dimensionQuery.getKey());
            Object response = cognee.post().uri("/api/v1/search").body(request).retrieve().body(Object.class);
            List<RepositoryEvidence> retrieved = RepositoryEvidence.extract(response, mapper, ingestion.getRepositoryUrl());
            log.info("Cognee evidence retrieval completed ingestionId={} dimension={} resultCount={}",
                    ingestion.getId(), dimensionQuery.getKey(), retrieved.size());
            for (RepositoryEvidence item : retrieved) {
                String key = String.valueOf(item.filePath()) + "\n" + item.text();
                unique.putIfAbsent(key, new RepositoryEvidence("e" + (unique.size() + 1), item.filePath(), item.text()));
            }
            index++;
        }
        List<RepositoryEvidence> evidence = List.copyOf(unique.values());
        if (evidence.isEmpty()) throw new IllegalStateException("Cognee returned no repository evidence");
        return evidence;
    }

    private EvaluationDtos.Scorecard createScorecard(IngestionJobEntity ingestion, List<RepositoryEvidence> evidence) {
        String evidenceJson;
        try { evidenceJson = mapper.writeValueAsString(evidence); }
        catch (Exception e) { throw new IllegalStateException("Could not serialize evidence for evaluation", e); }
        String system = "You evaluate software engineering demonstrated by a public code repository. " +
                "Score exactly these six dimensions from 0 to 5: architecture, maintainability, testing, reliability, security, performance/scalability. " +
                "Use only supplied evidence. Return a JSON object with summary and dimensions. Every dimension must include dimension, integer score, rationale, and citations. " +
                "Each citation must include evidenceId, filePath, positive startLine/endLine, and an exact short excerpt copied from that evidence. " +
                "Never invent file paths, line numbers, code, tests, or project behavior. If evidence is insufficient, use score 0, explain insufficient evidence, and use an empty citations array. " +
                "Return all six dimensions exactly once. Do not infer candidate identity or authorship from repository content.";
        String user = "Repository: " + ingestion.getRepositoryUrl() + "\nEvidence JSON:\n" + evidenceJson;
        EvaluationDtos.Scorecard result = chatClient.prompt().system(system).user(user).call().entity(EvaluationDtos.Scorecard.class);
        if (result == null || result.dimensions() == null) throw new IllegalStateException("Gemini returned an empty scorecard");
        return result;
    }

    private EvaluationDtos.EvaluationReport validateAndBuild(EvaluationJobEntity job, IngestionJobEntity ingestion,
                                                               EvaluationDtos.Scorecard scorecard,
                                                               List<RepositoryEvidence> evidence) {
        Map<String, RepositoryEvidence> byId = evidence.stream().collect(java.util.stream.Collectors.toMap(
                RepositoryEvidence::id, item -> item, (first, ignored) -> first));
        Map<String, EvaluationDtos.DimensionScore> byName = scorecard.dimensions().stream()
                .collect(java.util.stream.Collectors.toMap(item -> normalize(item.dimension()), item -> item,
                        (first, ignored) -> first));
        List<EvaluationDtos.DimensionScore> normalized = new ArrayList<>();
        for (String dimension : DIMENSIONS) {
            EvaluationDtos.DimensionScore source = byName.get(dimension);
            if (source == null || source.score() < 0 || source.score() > 5) {
                normalized.add(insufficient(dimension));
                continue;
            }
            List<EvaluationDtos.Citation> citations = source.citations() == null ? List.of()
                    : source.citations().stream().filter(citation -> validCitation(citation, byId)).toList();
            if (citations.isEmpty()) {
                normalized.add(insufficient(dimension));
            } else {
                normalized.add(new EvaluationDtos.DimensionScore(dimension, source.score(),
                        StringUtils.hasText(source.rationale()) ? source.rationale() : "Supported by cited repository evidence.", citations));
            }
        }
        int total = normalized.stream().mapToInt(EvaluationDtos.DimensionScore::score).sum();
        int overall = Math.round(total * 100.0f / 30.0f);
        String summary = "Scores use the six equal-weight dimensions below; each dimension includes its retrieved evidence.";
        return new EvaluationDtos.EvaluationReport(job.getId(), ingestion.getId(), ingestion.getRepositoryUrl(),
                overall, summary, normalized, job.getCreatedAt(), java.time.Instant.now());
    }

    private static boolean validCitation(EvaluationDtos.Citation citation, Map<String, RepositoryEvidence> evidence) {
        if (citation == null || !StringUtils.hasText(citation.evidenceId()) || !StringUtils.hasText(citation.filePath())
                || !StringUtils.hasText(citation.excerpt()) || citation.startLine() < 1
                || citation.endLine() < citation.startLine()) return false;
        RepositoryEvidence source = evidence.get(citation.evidenceId());
        if (source == null || !StringUtils.hasText(source.filePath())
                || !source.text().contains(citation.excerpt())) return false;
        return source.filePath().equals(citation.filePath());
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
