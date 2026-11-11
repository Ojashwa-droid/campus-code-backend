package com.campuscode.api;

import java.time.Instant;
import java.util.UUID;

public record IngestionStatus(UUID id, String repositoryUrl, String datasetName, State status,
                              Instant createdAt, Instant updatedAt, String error) {
    public enum State { QUEUED, INGESTING, COGNIFYING, COMPLETED, FAILED }
}
