package com.campuscode.service;

import com.campuscode.api.EvaluationDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fans evaluation events out to connected clients. Disconnects only remove a listener; the job keeps running. */
@Component
public class EvaluationStreamHub {
    private static final Logger log = LoggerFactory.getLogger(EvaluationStreamHub.class);

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> listeners = new ConcurrentHashMap<>();
    private final long timeoutMillis;

    public EvaluationStreamHub(@Value("${campuscode.evaluation.stream-timeout:1800000}") long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public SseEmitter subscribe(UUID evaluationId) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        register(evaluationId, emitter);
        return emitter;
    }

    void register(UUID evaluationId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> subscribers = listeners.computeIfAbsent(evaluationId,
                ignored -> new CopyOnWriteArrayList<>());
        subscribers.add(emitter);
        emitter.onCompletion(() -> remove(evaluationId, emitter));
        emitter.onTimeout(() -> remove(evaluationId, emitter));
        emitter.onError(error -> remove(evaluationId, emitter));
    }

    public void sendSnapshot(SseEmitter emitter, EvaluationDtos.EvaluationProgress progress) {
        send(emitter, "progress", progress);
    }

    public void publishProgress(UUID evaluationId, EvaluationDtos.EvaluationProgress progress) {
        publish(evaluationId, "progress", progress, false);
    }

    public void publishCompleted(UUID evaluationId, EvaluationDtos.EvaluationReport report) {
        publish(evaluationId, "completed", report, true);
    }

    public void publishFailed(UUID evaluationId, EvaluationDtos.EvaluationStatus status) {
        publish(evaluationId, "failed", status, true);
    }

    public void sendCompleted(SseEmitter emitter, EvaluationDtos.EvaluationReport report) {
        send(emitter, "completed", report);
        emitter.complete();
    }

    public void sendFailed(SseEmitter emitter, EvaluationDtos.EvaluationStatus status) {
        send(emitter, "failed", status);
        emitter.complete();
    }

    private void publish(UUID id, String eventName, Object body, boolean terminal) {
        CopyOnWriteArrayList<SseEmitter> subscribers = listeners.get(id);
        if (subscribers == null) return;
        for (SseEmitter emitter : subscribers) {
            send(emitter, eventName, body);
            if (terminal) {
                emitter.complete();
                remove(id, emitter);
            }
        }
    }

    private void send(SseEmitter emitter, String eventName, Object body) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(eventName).data(body, MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("Removing disconnected evaluation stream after event={} error={}", eventName, e.getMessage());
            for (var entry : listeners.entrySet()) remove(entry.getKey(), emitter);
        }
    }

    private void remove(UUID id, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> subscribers = listeners.get(id);
        if (subscribers == null) return;
        subscribers.remove(emitter);
        if (subscribers.isEmpty()) listeners.remove(id, subscribers);
    }
}
