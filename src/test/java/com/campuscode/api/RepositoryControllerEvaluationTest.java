package com.campuscode.api;

import com.campuscode.persistence.EvaluationJobEntity;
import com.campuscode.service.RepositoryMemoryService;
import com.campuscode.service.GraphVisualizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RepositoryControllerEvaluationTest {
    private RepositoryMemoryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RepositoryMemoryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RepositoryController(service, mock(GraphVisualizationService.class))).build();
    }

    @Test
    void existingEvaluationPostStillReturnsAcceptedJsonStatus() throws Exception {
        UUID ingestionId = UUID.randomUUID();
        EvaluationDtos.EvaluationStatus queued = new EvaluationDtos.EvaluationStatus(UUID.randomUUID(), ingestionId,
                "https://github.com/example/repo", EvaluationJobEntity.State.QUEUED, Instant.now(), Instant.now(), null);
        when(service.startEvaluation(ingestionId)).thenReturn(queued);

        mvc.perform(post("/api/repositories/ingestions/{id}/evaluations", ingestionId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void existingStatusAndReportGetsRemainAvailable() throws Exception {
        UUID evaluationId = UUID.randomUUID();
        UUID ingestionId = UUID.randomUUID();
        EvaluationDtos.EvaluationStatus evaluating = new EvaluationDtos.EvaluationStatus(evaluationId, ingestionId,
                "https://github.com/example/repo", EvaluationJobEntity.State.EVALUATING, Instant.now(), Instant.now(), null);
        EvaluationDtos.EvaluationReport report = new EvaluationDtos.EvaluationReport(evaluationId, ingestionId,
                "https://github.com/example/repo", 75, "Saved report", List.of(), Instant.now(), Instant.now());
        when(service.getEvaluationStatus(evaluationId)).thenReturn(evaluating);
        when(service.getReportForIngestion(ingestionId)).thenReturn(report);

        mvc.perform(get("/api/repositories/evaluations/{id}", evaluationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EVALUATING"));
        mvc.perform(get("/api/repositories/ingestions/{id}/report", ingestionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallScore").value(75));
    }

    @Test
    void evidenceBundleIsAvailableThroughAnAdditionalReadRoute() throws Exception {
        UUID ingestionId = UUID.randomUUID();
        EvaluationDtos.EvidenceBundle evidence = new EvaluationDtos.EvidenceBundle(UUID.randomUUID(), ingestionId,
                "https://github.com/example/repo", List.of(), List.of());
        when(service.getEvidenceForIngestion(ingestionId)).thenReturn(evidence);

        mvc.perform(get("/api/repositories/ingestions/{id}/report/evidence", ingestionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ingestionId").value(ingestionId.toString()))
                .andExpect(jsonPath("$.evidence").isArray())
                .andExpect(jsonPath("$.graphContext").isArray());
    }

    @Test
    void reportGraphContextIsAvailableThroughSpringApi() throws Exception {
        UUID ingestionId = UUID.randomUUID();
        EvaluationDtos.GraphContextGraph graph = new EvaluationDtos.GraphContextGraph(UUID.randomUUID(), ingestionId,
                "https://github.com/example/repo", true, List.of(), List.of());
        when(service.getGraphContextForIngestion(ingestionId)).thenReturn(graph);

        mvc.perform(get("/api/repositories/ingestions/{id}/report/graph-context", ingestionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ingestionId").value(ingestionId.toString()))
                .andExpect(jsonPath("$.edgesFromCurrentGraph").value(true))
                .andExpect(jsonPath("$.nodes").isArray())
                .andExpect(jsonPath("$.edges").isArray());
    }

    @Test
    void streamIsAnAdditionalPostRouteWithEventStreamResponse() {
        try {
            PostMapping mapping = RepositoryController.class
                    .getMethod("evaluateStream", UUID.class).getAnnotation(PostMapping.class);
            assertThat(mapping.value()).contains("/ingestions/{id}/evaluations/stream");
            assertThat(mapping.produces()).contains(MediaType.TEXT_EVENT_STREAM_VALUE);

            UUID ingestionId = UUID.randomUUID();
            SseEmitter emitter = new SseEmitter(60_000L);
            when(service.streamEvaluation(ingestionId)).thenReturn(emitter);
            assertThat(new RepositoryController(service, mock(GraphVisualizationService.class)).evaluateStream(ingestionId)).isSameAs(emitter);
            verify(service).streamEvaluation(ingestionId);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Streaming evaluation route is missing", e);
        }
    }
}
