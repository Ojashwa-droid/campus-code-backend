package com.campuscode.service;

import com.campuscode.api.EvaluationDtos;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Small bounded selector for the evidence passed to Gemini. */
final class EvaluationContextBuilder {
    private static final List<String> DIMENSIONS = List.of("architecture", "maintainability", "testing",
            "reliability", "security", "performance/scalability");
    private final ObjectMapper mapper;
    private final int maxEstimatedTokens;
    private final int charsPerToken;
    private final int maxPerDimension;
    private final int maxEvidenceChars;
    private final int maxGraphFacts;
    private final int maxGraphDescriptionChars;

    EvaluationContextBuilder(ObjectMapper mapper, int maxEstimatedTokens, int charsPerToken,
                             int maxPerDimension, int maxEvidenceChars, int maxGraphFacts,
                             int maxGraphDescriptionChars) {
        this.mapper = mapper;
        this.maxEstimatedTokens = Math.max(1, maxEstimatedTokens);
        this.charsPerToken = Math.max(1, charsPerToken);
        this.maxPerDimension = Math.max(1, maxPerDimension);
        this.maxEvidenceChars = Math.max(128, maxEvidenceChars);
        this.maxGraphFacts = Math.max(0, maxGraphFacts);
        this.maxGraphDescriptionChars = Math.max(64, maxGraphDescriptionChars);
    }

    BuiltContext build(EvaluationDtos.EvidenceBundle retrieved, List<RepositoryEvidence> rawEvidence,
                       String systemPrompt, String repositoryUrl) {
        Map<String, RepositoryEvidence> sources = new LinkedHashMap<>();
        rawEvidence.forEach(item -> sources.put(item.id(), item));
        Map<String, Passage> uniquePassages = new LinkedHashMap<>();
        int discardedMissingId = 0;
        int discardedMissingSource = 0;
        int discardedMissingPath = 0;
        int discardedInvalidLines = 0;
        for (EvaluationDtos.RepositoryEvidenceItem item : retrieved.evidence()) {
            if (item == null) { discardedMissingSource++; continue; }
            if (item.evidenceId() == null || item.evidenceId().isBlank()) { discardedMissingId++; continue; }
            RepositoryEvidence source = sources.get(item.evidenceId());
            if (source == null || source.text() == null) { discardedMissingSource++; continue; }
            if (item.filePath() == null || source.filePath() == null) { discardedMissingPath++; continue; }
            if (item.startLine() < 1 || item.endLine() < item.startLine()
                    || source.startLine() < 1 || source.endLine() < source.startLine()) {
                discardedInvalidLines++;
                continue;
            }
            String key = item.filePath().replace('\\', '/').toLowerCase(Locale.ROOT) + "\n" + item.excerpt().strip();
            Passage passage = uniquePassages.computeIfAbsent(key, ignored -> new Passage(source));
            passage.dimensions.addAll(tags(item.dimensions(), item.dimension()));
        }

        Map<String, List<Passage>> byDimension = new LinkedHashMap<>();
        Map<String, Integer> perDimension = new LinkedHashMap<>();
        for (String dimension : DIMENSIONS) {
            List<Passage> passages = uniquePassages.values().stream().filter(p -> p.dimensions.contains(dimension))
                    .sorted(Comparator.comparingInt((Passage p) -> priority(p.source.filePath())).reversed()).toList();
            byDimension.put(dimension, passages);
            perDimension.put(dimension, 0);
        }

        Map<String, EvaluationDtos.RepositoryEvidenceItem> selectedPassages = new LinkedHashMap<>();
        Map<String, RepositoryEvidence> citationSources = new LinkedHashMap<>();
        // Round robin across dimensions, preferring code and tests over documentation.
        for (int priority = 3; priority >= 0; priority--) {
            int currentPriority = priority;
            boolean added;
            do {
                added = false;
                for (String dimension : DIMENSIONS) {
                    if (perDimension.get(dimension) >= maxPerDimension) continue;
                    Passage next = byDimension.get(dimension).stream().filter(p -> !selectedPassages.containsKey(p.source.id())
                            && priority(p.source.filePath()) == currentPriority).findFirst().orElse(null);
                    if (next == null) continue;
                    EvaluationDtos.RepositoryEvidenceItem item = toItem(next);
                    selectedPassages.put(item.evidenceId(), item);
                    citationSources.put(item.evidenceId(), next.source);
                    next.dimensions.forEach(tag -> perDimension.computeIfPresent(tag, (ignored, count) -> count + 1));
                    added = true;
                }
            } while (added);
        }

        Map<String, Fact> uniqueFacts = new LinkedHashMap<>();
        for (EvaluationDtos.GraphFact fact : retrieved.graphContext()) {
            if (fact == null || fact.id() == null || fact.id().isBlank()) continue;
            Fact merged = uniqueFacts.computeIfAbsent(fact.id(), ignored -> new Fact(fact));
            merged.dimensions.addAll(tags(fact.dimensions(), fact.dimension()));
        }
        Map<String, EvaluationDtos.GraphFact> selectedFacts = new LinkedHashMap<>();
        Map<String, List<Fact>> factsByDimension = new LinkedHashMap<>();
        for (String dimension : DIMENSIONS) factsByDimension.put(dimension, uniqueFacts.values().stream()
                .filter(fact -> fact.dimensions.contains(dimension))
                .sorted(Comparator.comparing((Fact fact) -> fact.original.scoreable()).reversed()).toList());
        boolean added;
        do {
            added = false;
            for (String dimension : DIMENSIONS) {
                if (selectedFacts.size() >= maxGraphFacts) break;
                Fact next = factsByDimension.get(dimension).stream()
                        .filter(fact -> !selectedFacts.containsKey(fact.original.id())).findFirst().orElse(null);
                if (next != null) {
                    selectedFacts.put(next.original.id(), next.toFact(maxGraphDescriptionChars));
                    added = true;
                }
            }
        } while (added && selectedFacts.size() < maxGraphFacts);

        Map<String, EvaluationDtos.GraphEdge> selectedEdges = new LinkedHashMap<>();
        int maxGraphEdges = Math.max(0, maxGraphFacts * 4);
        for (EvaluationDtos.GraphEdge edge : retrieved.graphEdges() == null ? List.<EvaluationDtos.GraphEdge>of() : retrieved.graphEdges()) {
            if (edge == null || edge.sourceId() == null || edge.targetId() == null || edge.label() == null) continue;
            String key = edge.sourceId() + "\u0000" + edge.targetId() + "\u0000" + edge.label();
            EvaluationDtos.GraphEdge existing = selectedEdges.get(key);
            if (existing != null) {
                Set<String> mergedTags = new LinkedHashSet<>(tags(existing.dimensions(), null));
                mergedTags.addAll(tags(edge.dimensions(), null));
                selectedEdges.put(key, new EvaluationDtos.GraphEdge(edge.sourceId(), edge.targetId(), edge.label(),
                        DIMENSIONS.stream().filter(mergedTags::contains).toList()));
            } else if (selectedEdges.size() < maxGraphEdges) {
                selectedEdges.put(key, edge);
            }
        }

        EvaluationDtos.EvidenceBundle bounded = bundle(retrieved, selectedPassages, selectedFacts, selectedEdges);
        int estimate = estimate(systemPrompt, repositoryUrl, serialize(bounded));
        // Keep scoreable symbols and one symbol per dimension before supporting graph facts.
        while (estimate > maxEstimatedTokens && !selectedFacts.isEmpty()) {
            String removeId = selectedFacts.values().stream().filter(fact -> !fact.scoreable())
                    .map(EvaluationDtos.GraphFact::id).reduce((first, second) -> second).orElse(null);
            if (removeId == null) {
                removeId = selectedFacts.values().stream()
                        .filter(fact -> fact.dimensions().stream().allMatch(dimension -> selectedFacts.values().stream()
                                .filter(other -> other.scoreable() && other.dimensions().contains(dimension)).count() > 1))
                        .map(EvaluationDtos.GraphFact::id).reduce((first, second) -> second).orElse(null);
            }
            if (removeId == null) break;
            selectedFacts.remove(removeId);
            bounded = bundle(retrieved, selectedPassages, selectedFacts, selectedEdges);
            estimate = estimate(systemPrompt, repositoryUrl, serialize(bounded));
        }
        while (estimate > maxEstimatedTokens && !selectedPassages.isEmpty()) {
            EvaluationDtos.RepositoryEvidenceItem remove = selectedPassages.values().stream()
                    .filter(item -> item.dimensions().stream().allMatch(d -> perDimension.getOrDefault(d, 0) > 1))
                    .min(Comparator.comparingInt((EvaluationDtos.RepositoryEvidenceItem item) -> priority(item.filePath()))
                            .thenComparingInt(item -> -item.excerpt().length())).orElse(selectedPassages.values().iterator().next());
            selectedPassages.remove(remove.evidenceId());
            citationSources.remove(remove.evidenceId());
            remove.dimensions().forEach(tag -> perDimension.computeIfPresent(tag, (ignored, count) -> Math.max(0, count - 1)));
            bounded = bundle(retrieved, selectedPassages, selectedFacts, selectedEdges);
            estimate = estimate(systemPrompt, repositoryUrl, serialize(bounded));
        }
        if (estimate > maxEstimatedTokens) throw new IllegalStateException("Evaluation instructions exceed the configured Gemini context budget");

        int selectedCode = (int) selectedPassages.values().stream().filter(item -> priority(item.filePath()) == 3).count();
        int selectedDocs = (int) selectedPassages.values().stream().filter(item -> priority(item.filePath()) == 0).count();
        boolean trimmed = selectedPassages.values().stream().anyMatch(item -> item.excerpt().length() < citationSources.get(item.evidenceId()).text().length())
                || uniquePassages.size() > selectedPassages.size() || uniqueFacts.size() > selectedFacts.size();
        Map<String, Integer> coverage = new LinkedHashMap<>();
        for (String dimension : DIMENSIONS) coverage.put(dimension, (int) selectedPassages.values().stream()
                .filter(item -> item.dimensions().contains(dimension)).count());
        int scoreableSymbols = (int) selectedFacts.values().stream().filter(EvaluationDtos.GraphFact::scoreable).count();
        Map<String, Integer> symbolCoverage = new LinkedHashMap<>();
        for (String dimension : DIMENSIONS) symbolCoverage.put(dimension, (int) selectedFacts.values().stream()
                .filter(fact -> fact.scoreable() && fact.dimensions().contains(dimension)).count());
        return new BuiltContext(bounded, List.copyOf(citationSources.values()), serialize(bounded), estimate,
                Math.max(0, uniquePassages.size() - selectedPassages.size()),
                Math.max(0, uniqueFacts.size() - selectedFacts.size()), Map.copyOf(coverage), selectedCode,
                selectedDocs, selectedPassages.size() - selectedCode - selectedDocs, trimmed,
                retrieved.evidence().size(), discardedMissingId, discardedMissingSource, discardedMissingPath,
                discardedInvalidLines, scoreableSymbols, Map.copyOf(symbolCoverage));
    }

    private EvaluationDtos.RepositoryEvidenceItem toItem(Passage passage) {
        RepositoryEvidence source = passage.source;
        String excerpt = source.text().substring(0, Math.min(source.text().length(), maxEvidenceChars));
        int endLine = Math.min(source.endLine(), source.startLine() + (int) excerpt.lines().count() - 1);
        List<String> dimensions = DIMENSIONS.stream().filter(passage.dimensions::contains).toList();
        return new EvaluationDtos.RepositoryEvidenceItem(source.id(), dimensions.getFirst(), source.filePath(),
                source.startLine(), endLine, excerpt, dimensions, evidenceKind(source.filePath()));
    }

    private static String evidenceKind(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (normalized.contains("/test/") || normalized.contains("/tests/")
                || filename.matches(".*(?:test|tests|spec)[-_]?[^/]*\\.[^.]+$")) return "TEST_CODE";
        if (Set.of(".java", ".kt", ".py", ".js", ".jsx", ".ts", ".tsx", ".go", ".rs", ".cs", ".cpp", ".c")
                .stream().anyMatch(filename::endsWith)) return "SOURCE_CODE";
        if (Set.of(".md", ".rst", ".txt", ".adoc").stream().anyMatch(filename::endsWith)
                || normalized.contains("/docs/")) return "SUPPORTING_DOCUMENTATION";
        return "SUPPORTING_OTHER";
    }

    private EvaluationDtos.EvidenceBundle bundle(EvaluationDtos.EvidenceBundle source,
                                                  Map<String, EvaluationDtos.RepositoryEvidenceItem> passages,
                                                  Map<String, EvaluationDtos.GraphFact> facts,
                                                  Map<String, EvaluationDtos.GraphEdge> edges) {
        Set<String> selectedNodeIds = facts.keySet();
        List<EvaluationDtos.GraphEdge> selectedGraphEdges = edges.values().stream()
                .filter(edge -> selectedNodeIds.contains(edge.sourceId()) && selectedNodeIds.contains(edge.targetId())).toList();
        return new EvaluationDtos.EvidenceBundle(source.evaluationId(), source.ingestionId(), source.repositoryUrl(),
                List.copyOf(passages.values()), List.copyOf(facts.values()), selectedGraphEdges, source.architecture());
    }

    private String serialize(EvaluationDtos.EvidenceBundle bundle) {
        try { return mapper.writeValueAsString(bundle); }
        catch (Exception e) { throw new IllegalStateException("Could not serialize bounded Gemini context", e); }
    }

    private int estimate(String system, String repository, String evidenceJson) {
        int chars = system.length() + ("Repository: " + repository + "\nEvidence JSON:\n").length() + evidenceJson.length();
        return (int) Math.ceil(chars / (double) charsPerToken * 1.12);
    }

    private static int priority(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (normalized.contains("/test/") || normalized.contains("/tests/")
                || filename.matches(".*(?:test|tests|spec)[-_]?[^/]*\\.[^.]+$")
                || Set.of(".java", ".kt", ".py", ".js", ".jsx", ".ts", ".tsx", ".go", ".rs", ".cs", ".cpp", ".c")
                .stream().anyMatch(filename::endsWith)) return 3;
        if (Set.of(".yml", ".yaml", ".xml", ".json", ".toml", ".properties", ".gradle", ".sql")
                .stream().anyMatch(filename::endsWith)) return 2;
        if (Set.of(".md", ".rst", ".txt", ".adoc").stream().anyMatch(filename::endsWith)
                || normalized.contains("/docs/")) return 0;
        return 1;
    }

    private static List<String> tags(List<String> dimensions, String fallback) {
        if (dimensions != null && !dimensions.isEmpty()) return dimensions.stream()
                .filter(d -> d != null && !d.isBlank()).toList();
        return fallback == null || fallback.isBlank() ? List.of() : List.of(fallback);
    }

    record BuiltContext(EvaluationDtos.EvidenceBundle bundle, List<RepositoryEvidence> evidence, String json,
                        int estimatedTokens, int omittedEvidence, int omittedGraphFacts,
                        Map<String, Integer> evidencePerDimension, int sourceCodePassages,
                        int documentationPassages, int otherPassages, boolean truncated,
                        int retrievedEvidenceItems, int discardedMissingId, int discardedMissingSource,
                        int discardedMissingPath, int discardedInvalidLines, int scoreableGraphSymbols,
                        Map<String, Integer> graphSymbolsPerDimension) { }

    private static final class Passage {
        private final RepositoryEvidence source;
        private final Set<String> dimensions = new LinkedHashSet<>();
        private Passage(RepositoryEvidence source) { this.source = source; }
    }

    private static final class Fact {
        private final EvaluationDtos.GraphFact original;
        private final Set<String> dimensions = new LinkedHashSet<>();
        private Fact(EvaluationDtos.GraphFact original) { this.original = original; }
        private EvaluationDtos.GraphFact toFact(int maxChars) {
            String description = original.description() == null ? "" : original.description();
            if (description.length() > maxChars) description = description.substring(0, maxChars) + "…";
            List<String> tags = DIMENSIONS.stream().filter(dimensions::contains).toList();
            return new EvaluationDtos.GraphFact(original.id(), tags.getFirst(), original.label(), original.type(),
                    description, original.filePath(), original.relationships(), tags, original.symbolName(),
                    original.symbolType(), original.language(), original.repository(), original.line(), original.scoreable());
        }
    }
}
