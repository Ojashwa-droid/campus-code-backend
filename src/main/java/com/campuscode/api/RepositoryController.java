package com.campuscode.api;

import com.campuscode.service.RepositoryMemoryService;
import com.campuscode.service.GraphVisualizationService;
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
@CrossOrigin(origins = "*")
public class RepositoryController {
    private final RepositoryMemoryService service;
    private final GraphVisualizationService graphVisualizationService;

    public RepositoryController(RepositoryMemoryService service, GraphVisualizationService graphVisualizationService) {
        this.service = service;
        this.graphVisualizationService = graphVisualizationService;
    }

    @GetMapping("/")
    public String home() {
        return "Tunnel is working!";
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

    @GetMapping(value = "/graph/visualization", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> graphVisualization(@RequestParam String repositoryUrl) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("X-Content-Type-Options", "nosniff")
                .body(graphVisualizationService.visualizeRepository(repositoryUrl));
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

    @GetMapping("/ingestions/{id}/report/evidence")
    public EvaluationDtos.EvidenceBundle evaluationEvidence(@PathVariable UUID id) {
        return service.getEvidenceForIngestion(id);
    }

    @GetMapping("/ingestions/{id}/report/graph-context")
    public EvaluationDtos.GraphContextGraph evaluationGraphContext(@PathVariable UUID id) {
        return service.getGraphContextForIngestion(id);
    }

    @PostMapping("/ingestions/{id}/evaluation-context/preview")
    public EvaluationDtos.EvaluationContextPreview evaluationContextPreview(@PathVariable UUID id) {
        return service.previewEvaluationContext(id);
    }

    @PostMapping("/ingestions/{id}/evaluation-context/cognee-analysis-preview")
    public EvaluationDtos.CogneeRepositoryAnalysisPreview cogneeRepositoryAnalysisPreview(@PathVariable UUID id) {
        return service.previewCogneeRepositoryAnalysis(id);
    }

}
