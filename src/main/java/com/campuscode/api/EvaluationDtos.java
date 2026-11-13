package com.campuscode.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class EvaluationDtos {
    private EvaluationDtos() { }

    public record Scorecard(String summary, List<DimensionScore> dimensions) { }
    public record ModelScorecard(String summary, List<ModelDimensionScore> dimensions) { }
    public record ModelDimensionScore(String dimension, int score, String rationale, List<EvidencePoint> strengths,
                                      List<EvidencePoint> areasForImprovement, List<String> evidenceIds) { }
    public record DimensionScore(String dimension, int score, String rationale, List<EvidencePoint> strengths,
                                 List<EvidencePoint> areasForImprovement, List<Citation> citations) {
        public DimensionScore(String dimension, int score, String rationale, List<Citation> citations) {
            this(dimension, score, rationale, List.of(), List.of(), citations);
        }
    }
    public record EvidencePoint(String text, List<String> evidenceIds) { }
    public record Citation(String evidenceId, String filePath, Integer startLine, Integer endLine, String excerpt,
                           String symbolName, String symbolType, String referenceType) {
        public Citation(String evidenceId, String filePath, int startLine, int endLine, String excerpt) {
            this(evidenceId, filePath, startLine, endLine, excerpt, null, null, "SOURCE_EXCERPT");
        }
    }
    public record EvidenceBundle(UUID evaluationId, UUID ingestionId, String repositoryUrl,
                                 List<RepositoryEvidenceItem> evidence, List<GraphFact> graphContext,
                                 List<GraphEdge> graphEdges) {
        public EvidenceBundle(UUID evaluationId, UUID ingestionId, String repositoryUrl,
                              List<RepositoryEvidenceItem> evidence, List<GraphFact> graphContext) {
            this(evaluationId, ingestionId, repositoryUrl, evidence, graphContext, List.of());
        }
    }
    public record GraphEdge(String sourceId, String targetId, String label, List<String> dimensions) {
        public GraphEdge(String sourceId, String targetId, String label) {
            this(sourceId, targetId, label, List.of());
        }
    }
    public record GraphContextGraph(UUID evaluationId, UUID ingestionId, String repositoryUrl,
                                    boolean edgesFromCurrentGraph, List<GraphFact> nodes,
                                    List<GraphEdge> edges) { }
    public record RepositoryEvidenceItem(String evidenceId, String dimension, String filePath,
                                         int startLine, int endLine, String excerpt, List<String> dimensions) {
        public RepositoryEvidenceItem(String evidenceId, String dimension, String filePath,
                                       int startLine, int endLine, String excerpt) {
            this(evidenceId, dimension, filePath, startLine, endLine, excerpt,
                    dimension == null ? List.of() : List.of(dimension));
        }
    }
    public record GraphFact(String id, String dimension, String label, String type, String description,
                            String filePath, List<String> relationships, List<String> dimensions,
                            String symbolName, String symbolType, String language, String repository,
                            Integer line, boolean scoreable) {
        public GraphFact(String id, String dimension, String label, String type, String description,
                         String filePath, List<String> relationships) {
            this(id, dimension, label, type, description, filePath, relationships,
                    dimension == null ? List.of() : List.of(dimension), label, type, null, null, null, false);
        }
        public GraphFact(String id, String dimension, String label, String type, String description,
                         String filePath, List<String> relationships, List<String> dimensions) {
            this(id, dimension, label, type, description, filePath, relationships, dimensions,
                    label, type, null, null, null, false);
        }
    }

    public record EvaluationStatus(UUID id, UUID ingestionId, String repositoryUrl,
                                   com.campuscode.persistence.EvaluationJobEntity.State status,
                                   Instant createdAt, Instant updatedAt, String error) { }

    public record EvaluationProgress(UUID evaluationId, UUID ingestionId,
                                     com.campuscode.persistence.EvaluationJobEntity.State status,
                                     String stage, int progress, String message, Instant updatedAt) { }

    public record EvaluationReport(UUID id, UUID ingestionId, String repositoryUrl, int overallScore,
                                   String summary, List<DimensionScore> dimensions,
                                   Instant createdAt, Instant completedAt,
                                   String citationMode, String defaultBranch) {
        public EvaluationReport(UUID id, UUID ingestionId, String repositoryUrl, int overallScore,
                                String summary, List<DimensionScore> dimensions, Instant createdAt, Instant completedAt) {
            this(id, ingestionId, repositoryUrl, overallScore, summary, dimensions, createdAt, completedAt, null, null);
        }
    }
}
