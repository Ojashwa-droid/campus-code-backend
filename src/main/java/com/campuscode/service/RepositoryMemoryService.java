package com.campuscode.service;

import com.campuscode.api.EvaluationDtos;
import com.campuscode.api.IngestionStatus;
import com.campuscode.persistence.EvaluationJobEntity;
import com.campuscode.persistence.EvaluationJobRepository;
import com.campuscode.persistence.IngestionJobEntity;
import com.campuscode.persistence.IngestionJobRepository;
import com.campuscode.repository.RepositoryUrl;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

@Service
public class RepositoryMemoryService {
    private static final Logger log = LoggerFactory.getLogger(RepositoryMemoryService.class);
    private final RestClient cognee;
    private final ExecutorService worker;
    private final IngestionJobRepository ingestions;
    private final EvaluationJobRepository evaluations;
    private final EvaluationService evaluationService;
    private final Map<String, Object> repositoryLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public RepositoryMemoryService(RestClient cognee, ExecutorService worker,
                                   IngestionJobRepository ingestions, EvaluationJobRepository evaluations,
                                   EvaluationService evaluationService) {
        this.cognee = cognee;
        this.worker = worker;
        this.ingestions = ingestions;
        this.evaluations = evaluations;
        this.evaluationService = evaluationService;
    }

    @PostConstruct
    void markInterruptedJobsAfterRestart() {
        List<IngestionStatus.State> interruptedIngestion = List.of(IngestionStatus.State.QUEUED,
                IngestionStatus.State.INGESTING, IngestionStatus.State.COGNIFYING);
        for (IngestionJobEntity job : ingestions.findByStatusIn(interruptedIngestion)) {
            job.transition(IngestionStatus.State.FAILED, "Application restarted while ingestion was in progress; submit again to retry.");
            ingestions.save(job);
        }
        for (EvaluationJobEntity job : evaluations.findAll()) {
            if (job.getStatus() == EvaluationJobEntity.State.QUEUED
                    || job.getStatus() == EvaluationJobEntity.State.EVALUATING) {
                job.transition(EvaluationJobEntity.State.FAILED, null,
                        "Application restarted while evaluation was in progress; submit again to retry.");
                evaluations.save(job);
            }
        }
    }

    public IngestionStatus startIngestion(String url, String notificationEmail) {
        RepositoryUrl repository = RepositoryUrl.parse(url);
        Object lock = repositoryLocks.computeIfAbsent(repository.datasetName(), ignored -> new Object());
        synchronized (lock) {
            List<IngestionJobEntity> previousJobs = ingestions.findByRepositoryUrlOrderByCreatedAtDesc(repository.canonicalUrl());
            IngestionJobEntity previous = previousJobs.isEmpty() ? null : previousJobs.getFirst();
            if (previous != null && previous.getStatus() != IngestionStatus.State.FAILED) {
                log.info("Duplicate ingestion request repository={} dataset={} existingJobId={} status={}",
                        repository.canonicalUrl(), repository.datasetName(), previous.getId(), previous.getStatus());
                return previous.toStatus();
            }

            IngestionJobEntity job = ingestions.save(new IngestionJobEntity(UUID.randomUUID(),
                    repository.canonicalUrl(), repository.datasetName(), notificationEmail));
            log.info("Accepted ingestion job jobId={} repository={} dataset={} status={}", job.getId(),
                    job.getRepositoryUrl(), job.getDatasetName(), job.getStatus());
            try {
                worker.submit(() -> runIngestion(job.getId()));
            } catch (RuntimeException e) {
                job.transition(IngestionStatus.State.FAILED, "Unable to schedule ingestion worker");
                ingestions.save(job);
                throw e;
            }
            return job.toStatus();
        }
    }

    public IngestionStatus getIngestion(UUID id) {
        IngestionJobEntity job = ingestions.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ingestion not found: " + id));
        log.debug("Ingestion status requested jobId={} status={}", id, job.getStatus());
        return job.toStatus();
    }

    public EvaluationDtos.EvaluationStatus startEvaluation(UUID ingestionId) {
        return evaluationService.start(ingestionId);
    }

    public SseEmitter streamEvaluation(UUID ingestionId) {
        return evaluationService.stream(ingestionId);
    }

    public EvaluationDtos.EvaluationStatus getEvaluationStatus(UUID evaluationId) {
        return evaluationService.getStatus(evaluationId);
    }

    public EvaluationDtos.EvaluationReport getReportForIngestion(UUID ingestionId) {
        return evaluationService.getReportForIngestion(ingestionId);
    }

    public EvaluationDtos.EvidenceBundle getEvidenceForIngestion(UUID ingestionId) {
        return evaluationService.getEvidenceForIngestion(ingestionId);
    }

    public EvaluationDtos.GraphContextGraph getGraphContextForIngestion(UUID ingestionId) {
        return evaluationService.getGraphContextForIngestion(ingestionId);
    }

    /** Retrieval-only endpoint: Cognee returns passages; the API does not ask Cognee to generate an answer. */
    public Map<String, Object> search(String url, String query) {
        RepositoryUrl repository = RepositoryUrl.parse(url);
        long startedAt = System.nanoTime();
        log.info("Starting repository evidence search repository={} dataset={} queryLength={}",
                repository.canonicalUrl(), repository.datasetName(), query.length());
        Map<String, Object> request = Map.of("query", query,
                "datasets", new String[]{repository.datasetName()}, "search_type", "CHUNKS", "top_k", 20);
        try {
            Object response = cognee.post().uri("/api/v1/search")
                    .contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Object.class);
            log.info("Repository evidence search completed repository={} dataset={} durationMs={}",
                    repository.canonicalUrl(), repository.datasetName(), elapsedMillis(startedAt));
            return Map.of("repositoryUrl", repository.canonicalUrl(), "datasetName", repository.datasetName(),
                    "query", query, "evidence", response == null ? List.of() : response);
        } catch (RuntimeException exception) {
            log.error("Repository evidence search failed repository={} dataset={} durationMs={} error={}",
                    repository.canonicalUrl(), repository.datasetName(), elapsedMillis(startedAt),
                    exception.getMessage(), exception);
            throw exception;
        }
    }

    private void runIngestion(UUID id) {
        long startedAt = System.nanoTime();
        IngestionJobEntity job = ingestions.findById(id).orElseThrow();
        try {
            log.info("Ingestion worker started jobId={} repository={} dataset={} thread={}", id,
                    job.getRepositoryUrl(), job.getDatasetName(), Thread.currentThread().getName());
            update(job, IngestionStatus.State.INGESTING, null);
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("raw_data", job.getRepositoryUrl());
            form.add("datasetName", job.getDatasetName());
            long addStartedAt = System.nanoTime();
            log.info("Cognee add started jobId={} repository={} dataset={} endpoint=/api/v1/add", id,
                    job.getRepositoryUrl(), job.getDatasetName());
            var addResponse = cognee.post().uri("/api/v1/add").contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form).retrieve().toBodilessEntity();
            log.info("Cognee add completed jobId={} httpStatus={} durationMs={}", id,
                    addResponse.getStatusCode().value(), elapsedMillis(addStartedAt));

            update(job, IngestionStatus.State.COGNIFYING, null);
            long cognifyStartedAt = System.nanoTime();
            log.info("Cognee cognify started jobId={} dataset={} endpoint=/api/v1/cognify", id, job.getDatasetName());
            var cognifyResponse = cognee.post().uri("/api/v1/cognify").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("datasets", new String[]{job.getDatasetName()})).retrieve().toBodilessEntity();
            log.info("Cognee cognify completed jobId={} httpStatus={} durationMs={}", id,
                    cognifyResponse.getStatusCode().value(), elapsedMillis(cognifyStartedAt));
            update(job, IngestionStatus.State.COMPLETED, null);
            log.info("Ingestion completed jobId={} repository={} dataset={} durationMs={}", id,
                    job.getRepositoryUrl(), job.getDatasetName(), elapsedMillis(startedAt));
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            update(job, IngestionStatus.State.FAILED, message);
            log.error("Ingestion failed jobId={} repository={} dataset={} durationMs={} error={}", id,
                    job.getRepositoryUrl(), job.getDatasetName(), elapsedMillis(startedAt), message, exception);
        }
    }

    private void update(IngestionJobEntity job, IngestionStatus.State state, String error) {
        IngestionStatus.State previous = job.getStatus();
        job.transition(state, error);
        ingestions.save(job);
        log.info("Ingestion state changed jobId={} repository={} from={} to={}", job.getId(),
                job.getRepositoryUrl(), previous, state);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
