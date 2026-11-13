package com.campuscode.persistence;

import com.campuscode.api.IngestionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ingestion_jobs")
public class IngestionJobEntity {
    @Id
    private UUID id;

    @Column(nullable = false, length = 500)
    private String repositoryUrl;

    @Column(nullable = false, length = 100)
    private String datasetName;

    @Column(length = 36)
    private String cogneeDatasetId;

    @Column(length = 320)
    private String notificationEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private IngestionStatus.State status;

    @Column(columnDefinition = "text")
    private String error;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected IngestionJobEntity() { }

    public IngestionJobEntity(UUID id, String repositoryUrl, String datasetName, String notificationEmail) {
        this.id = id;
        this.repositoryUrl = repositoryUrl;
        this.datasetName = datasetName;
        this.notificationEmail = notificationEmail;
        this.status = IngestionStatus.State.QUEUED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void transition(IngestionStatus.State next, String error) {
        this.status = next;
        this.error = error;
        this.updatedAt = Instant.now();
    }

    public IngestionStatus toStatus() {
        return new IngestionStatus(id, repositoryUrl, datasetName, status, createdAt, updatedAt, error);
    }

    public UUID getId() { return id; }
    public String getRepositoryUrl() { return repositoryUrl; }
    public String getDatasetName() { return datasetName; }
    public String getCogneeDatasetId() { return cogneeDatasetId; }
    public void setCogneeDatasetId(String cogneeDatasetId) { this.cogneeDatasetId = cogneeDatasetId; }
    public String getNotificationEmail() { return notificationEmail; }
    public IngestionStatus.State getStatus() { return status; }
}
