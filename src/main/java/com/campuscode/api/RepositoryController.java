package com.campuscode.api;

import com.campuscode.service.RepositoryMemoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {
    private final RepositoryMemoryService service;

    public RepositoryController(RepositoryMemoryService service) {
        this.service = service;
    }

    @PostMapping("/ingestions")
    public ResponseEntity<IngestionStatus> ingest(@Valid @RequestBody IngestRepositoryRequest request) {
        IngestionStatus status = service.startIngestion(request.repositoryUrl(), request.notificationEmail());
        return ResponseEntity.status(status.status() == IngestionStatus.State.QUEUED
                || status.status() == IngestionStatus.State.INGESTING
                || status.status() == IngestionStatus.State.COGNIFYING
                ? HttpStatus.ACCEPTED : HttpStatus.OK).body(status);
    }

    @GetMapping("/ingestions/{id}")
    public IngestionStatus ingestionStatus(@PathVariable UUID id) {
        return service.getIngestion(id);
    }

    @PostMapping("/search")
    public Map<String, Object> search(@Valid @RequestBody SearchRepositoryRequest request) {
        return service.search(request.repositoryUrl(), request.query());
    }

    @PostMapping("/ingestions/{id}/evaluations")
    public ResponseEntity<EvaluationDtos.EvaluationStatus> evaluate(@PathVariable UUID id) {
        EvaluationDtos.EvaluationStatus status = service.startEvaluation(id);
        return ResponseEntity.status(status.status() == com.campuscode.persistence.EvaluationJobEntity.State.QUEUED
                || status.status() == com.campuscode.persistence.EvaluationJobEntity.State.EVALUATING
                ? HttpStatus.ACCEPTED : HttpStatus.OK).body(status);
    }

    @PostMapping(value = "/ingestions/{id}/evaluations/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter evaluateStream(@PathVariable UUID id) {
        return service.streamEvaluation(id);
    }

    @GetMapping("/evaluations/{id}")
    public EvaluationDtos.EvaluationStatus evaluationStatus(@PathVariable UUID id) {
        return service.getEvaluationStatus(id);
    }

    @GetMapping("/ingestions/{id}/report")
    public EvaluationDtos.EvaluationReport evaluationReport(@PathVariable UUID id) {
        return service.getReportForIngestion(id);
    }

}
