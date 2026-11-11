package com.campuscode.service;

import com.campuscode.api.EvaluationDtos;
import com.campuscode.api.IngestionStatus;
import com.campuscode.persistence.EvaluationJobEntity;
import com.campuscode.persistence.EvaluationJobRepository;
import com.campuscode.persistence.IngestionJobEntity;
import com.campuscode.persistence.IngestionJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationServiceStreamTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void streamingAnActiveEvaluationSubscribesToExistingJobWithoutLaunchingDuplicateWork() {
        UUID ingestionId = UUID.randomUUID();
        IngestionJobEntity ingestion = completedIngestion(ingestionId);
        EvaluationJobEntity job = new EvaluationJobEntity(UUID.randomUUID(), ingestionId, ingestion.getRepositoryUrl());
        job.transition(EvaluationJobEntity.State.EVALUATING, null, null);
        job.updateProgress("retrieving_evidence", 24);
        IngestionJobRepository ingestions = mock(IngestionJobRepository.class);
        EvaluationJobRepository evaluations = mock(EvaluationJobRepository.class);
        EvaluationStreamHub hub = mock(EvaluationStreamHub.class);
        ExecutorService worker = mock(ExecutorService.class);
        SseEmitter emitter = mock(SseEmitter.class);
        when(ingestions.findById(ingestionId)).thenReturn(Optional.of(ingestion));
        when(evaluations.findByIngestionId(ingestionId)).thenReturn(Optional.of(job));
        when(hub.subscribe(job.getId())).thenReturn(emitter);
        EvaluationService service = service(ingestions, evaluations, hub, worker);

        assertThat(service.stream(ingestionId)).isSameAs(emitter);

        verify(hub).sendSnapshot(any(SseEmitter.class), any(EvaluationDtos.EvaluationProgress.class));
        verify(worker, never()).submit(any(Runnable.class));
    }

    @Test
    void streamingAnAlreadyCompletedEvaluationImmediatelyReturnsPersistedReport() throws Exception {
        UUID ingestionId = UUID.randomUUID();
        IngestionJobEntity ingestion = completedIngestion(ingestionId);
        EvaluationJobEntity job = new EvaluationJobEntity(UUID.randomUUID(), ingestionId, ingestion.getRepositoryUrl());
        EvaluationDtos.EvaluationReport report = new EvaluationDtos.EvaluationReport(job.getId(), ingestionId,
                ingestion.getRepositoryUrl(), 72, "Saved report", List.of(), Instant.now(), Instant.now());
        job.transition(EvaluationJobEntity.State.COMPLETED, mapper.writeValueAsString(report), null);
        IngestionJobRepository ingestions = mock(IngestionJobRepository.class);
        EvaluationJobRepository evaluations = mock(EvaluationJobRepository.class);
        EvaluationStreamHub hub = mock(EvaluationStreamHub.class);
        ExecutorService worker = mock(ExecutorService.class);
        SseEmitter emitter = mock(SseEmitter.class);
        when(ingestions.findById(ingestionId)).thenReturn(Optional.of(ingestion));
        when(evaluations.findByIngestionId(ingestionId)).thenReturn(Optional.of(job));
        when(hub.subscribe(job.getId())).thenReturn(emitter);
        EvaluationService service = service(ingestions, evaluations, hub, worker);

        assertThat(service.stream(ingestionId)).isSameAs(emitter);

        verify(hub).sendCompleted(emitter, report);
        verify(worker, never()).submit(any(Runnable.class));
    }

    private EvaluationService service(IngestionJobRepository ingestions, EvaluationJobRepository evaluations,
                                     EvaluationStreamHub hub, ExecutorService worker) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new EvaluationService(mock(RestClient.class), builder, ingestions, evaluations, mapper,
                worker, hub, mock(JavaMailSender.class), "", "");
    }

    private static IngestionJobEntity completedIngestion(UUID id) {
        IngestionJobEntity ingestion = new IngestionJobEntity(id, "https://github.com/example/repo",
                "repo_example", null);
        ingestion.transition(IngestionStatus.State.COMPLETED, null);
        return ingestion;
    }
}
