package com.campuscode.service;

import com.campuscode.api.EvaluationDtos;
import com.campuscode.persistence.EvaluationJobEntity;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class EvaluationStreamHubTest {
    @Test
    void publishesProgressThenFinalReportAndClosesStream() throws Exception {
        EvaluationStreamHub hub = new EvaluationStreamHub(60_000);
        UUID id = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        hub.register(id, emitter);
        EvaluationDtos.EvaluationProgress progress = new EvaluationDtos.EvaluationProgress(id, UUID.randomUUID(),
                EvaluationJobEntity.State.EVALUATING, "generating_scorecard", 68, "Generating report.", Instant.now());
        EvaluationDtos.EvaluationReport report = new EvaluationDtos.EvaluationReport(id, progress.ingestionId(),
                "https://github.com/example/repo", 80, "Summary", java.util.List.of(), Instant.now(), Instant.now());

        hub.publishProgress(id, progress);
        hub.publishCompleted(id, report);

        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test
    void disconnectedClientIsRemovedWithoutFailingPublisherOrCancellingJob() throws Exception {
        EvaluationStreamHub hub = new EvaluationStreamHub(60_000);
        UUID id = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        hub.register(id, emitter);
        doThrow(new IOException("client disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        EvaluationDtos.EvaluationProgress progress = new EvaluationDtos.EvaluationProgress(id, UUID.randomUUID(),
                EvaluationJobEntity.State.EVALUATING, "retrieving_evidence", 16, "Searching evidence.", Instant.now());

        assertDoesNotThrow(() -> hub.publishProgress(id, progress));
        hub.publishProgress(id, progress);

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();
    }

    @Test
    void failedEvaluationEmitsTerminalFailureEvent() throws Exception {
        EvaluationStreamHub hub = new EvaluationStreamHub(60_000);
        UUID id = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        hub.register(id, emitter);
        EvaluationDtos.EvaluationStatus failed = new EvaluationDtos.EvaluationStatus(id, UUID.randomUUID(),
                "https://github.com/example/repo", EvaluationJobEntity.State.FAILED,
                Instant.now(), Instant.now(), "Gemini unavailable");

        hub.publishFailed(id, failed);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }
}
