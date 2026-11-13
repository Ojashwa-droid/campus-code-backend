package com.campuscode.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evaluation_jobs")
public class EvaluationJobEntity {
    public enum State { QUEUED, EVALUATING, COMPLETED, FAILED }

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID ingestionId;

    @Column(nullable = false)
    private String repositoryUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private State status;

    @Column(nullable = false, length = 48, columnDefinition = "varchar(48) default 'queued'")
    private String stage;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int progress;

    @Column(columnDefinition = "text")
    private String reportJson;

    @Column(columnDefinition = "text")
    private String evidenceJson;

    @Column(columnDefinition = "text")
    private String error;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected EvaluationJobEntity() { }

    public EvaluationJobEntity(UUID id, UUID ingestionId, String repositoryUrl) {
        this.id = id;
        this.ingestionId = ingestionId;
        this.repositoryUrl = repositoryUrl;
        this.status = State.QUEUED;
        this.stage = "queued";
        this.progress = 0;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void transition(State next, String reportJson, String error) {
        this.status = next;
        this.reportJson = reportJson;
        this.error = error;
        this.stage = switch (next) {
            case QUEUED -> "queued";
            case EVALUATING -> "retrieving_evidence";
            case COMPLETED -> "completed";
            case FAILED -> "failed";
        };
        if (next == State.QUEUED) this.progress = 0;
        if (next == State.COMPLETED) this.progress = 100;
        this.updatedAt = Instant.now();
    }

    public void updateProgress(String stage, int progress) {
        this.stage = stage;
        this.progress = Math.max(0, Math.min(progress, 99));
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getIngestionId() { return ingestionId; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public State getStatus() { return status; }
    public String getStage() { return stage; }
    public int getProgress() { return progress; }
    public String getReportJson() { return reportJson; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
