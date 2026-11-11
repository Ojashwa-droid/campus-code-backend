package com.campuscode.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EvaluationDtos {
    private EvaluationDtos() { }

    public record Scorecard(String summary, List<DimensionScore> dimensions) { }
    public record DimensionScore(String dimension, int score, String rationale, List<Citation> citations) { }
    public record Citation(String evidenceId, String filePath, int startLine, int endLine, String excerpt) { }

    public record EvaluationStatus(UUID id, UUID ingestionId, String repositoryUrl,
                                   com.campuscode.persistence.EvaluationJobEntity.State status,
                                   Instant createdAt, Instant updatedAt, String error) { }

    public record EvaluationProgress(UUID evaluationId, UUID ingestionId,
                                     com.campuscode.persistence.EvaluationJobEntity.State status,
                                     String stage, int progress, String message, Instant updatedAt) { }

    public record EvaluationReport(UUID id, UUID ingestionId, String repositoryUrl, int overallScore,
                                   String summary, List<DimensionScore> dimensions,
                                   Instant createdAt, Instant completedAt) { }
}
