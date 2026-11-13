package com.campuscode.service;

import com.campuscode.api.EvaluationDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Builds dimension-specific graph neighborhoods and identifies graph nodes safe to cite as symbols. */
final class RepositoryGraphExpander {
    private static final int MAX_NODES = 45;
    private static final int MAX_SEEDS = 12;
    private static final int MAX_DESCRIPTION = 500;
    private static final Pattern WORD = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{2,}");
    private static final Set<String> CODE_EXTENSIONS = Set.of(".java", ".kt", ".kts", ".py", ".js", ".jsx",
            ".ts", ".tsx", ".go", ".rs", ".cs", ".cpp", ".c", ".h", ".hpp", ".scala", ".rb",
            ".php", ".swift", ".dart", ".vue", ".sql");
    private static final Set<String> GENERIC_TERMS = Set.of("source", "code", "showing", "repository", "dimension",
            "component", "components", "application", "system", "project", "software", "engineering", "evidence",
            "relevant", "demonstrating", "behavior", "behavioral", "implementation");

    private RepositoryGraphExpander() { }

    static List<EvaluationDtos.GraphFact> expand(JsonNode graph, String dimension, String query,
                                                  List<RepositoryEvidence> hints) {
        String searchText = hints == null ? "" : hints.stream()
                .map(item -> item.filePath() + " " + item.text()).reduce("", (a, b) -> a + " " + b);
        return expand(graph, dimension, query, searchText, null, null);
    }

    static List<EvaluationDtos.GraphFact> expand(JsonNode graph, String dimension, String query,
                                                  Object codeSearch, Object chunkSearch, String repositoryUrl) {
        return expandDetailed(graph, dimension, query, codeSearch, chunkSearch, repositoryUrl).facts();
    }

    static Expansion expandDetailed(JsonNode graph, String dimension, String query,
                                    Object codeSearch, Object chunkSearch, String repositoryUrl) {
        JsonNode rawNodes = graph == null ? null : graph.path("nodes");
        JsonNode rawEdges = graph == null ? null : graph.path("edges");
        if (rawNodes == null || !rawNodes.isArray()) return new Expansion(List.of(), List.of());

        Map<String, JsonNode> nodes = new HashMap<>();
        Map<String, Set<String>> neighbors = new HashMap<>();
        rawNodes.forEach(node -> {
            String id = text(node, "id");
            if (id != null && belongsToRepository(node, repositoryUrl)) nodes.put(id, node);
        });
        if (rawEdges != null && rawEdges.isArray()) rawEdges.forEach(edge -> {
            String source = text(edge, "source");
            String target = text(edge, "target");
            if (source == null || target == null || !nodes.containsKey(source) || !nodes.containsKey(target)) return;
            neighbors.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(target);
            neighbors.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(source);
        });

        String searchText = flatten(codeSearch) + "\n" + flatten(chunkSearch);
        Set<String> queryTerms = terms(query);
        List<Map.Entry<String, JsonNode>> ranked = nodes.entrySet().stream()
                .filter(entry -> isScoreableSymbol(entry.getValue()))
                .sorted(Comparator.<Map.Entry<String, JsonNode>>comparingInt(entry ->
                        score(entry.getValue(), queryTerms, searchText)).reversed())
                .toList();

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (var entry : ranked) {
            int rank = score(entry.getValue(), queryTerms, searchText);
            if (rank <= 0 || selected.size() >= MAX_SEEDS) break;
            selected.add(entry.getKey());
        }
        if (selected.isEmpty()) return new Expansion(List.of(), List.of());

        ArrayDeque<DepthNode> queue = new ArrayDeque<>();
        selected.forEach(id -> queue.addLast(new DepthNode(id, 0)));
        while (!queue.isEmpty() && selected.size() < MAX_NODES) {
            DepthNode current = queue.removeFirst();
            if (current.depth() >= 2) continue;
            for (String adjacent : neighbors.getOrDefault(current.id(), Set.of())) {
                if (selected.add(adjacent)) queue.addLast(new DepthNode(adjacent, current.depth() + 1));
                if (selected.size() >= MAX_NODES) break;
            }
        }

        Map<String, Set<String>> boundedRelationships = new HashMap<>();
        List<EvaluationDtos.GraphEdge> edges = new ArrayList<>();
        if (rawEdges != null && rawEdges.isArray()) rawEdges.forEach(edge -> {
            String source = text(edge, "source");
            String target = text(edge, "target");
            String label = text(edge, "label");
            if (source == null || target == null || label == null || !selected.contains(source) || !selected.contains(target)) return;
            String sourceRelation = label + " → " + text(nodes.get(target), "label");
            String targetRelation = text(nodes.get(source), "label") + " → " + label;
            boundedRelationships.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(sourceRelation);
            boundedRelationships.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(targetRelation);
            edges.add(new EvaluationDtos.GraphEdge(source, target, label, List.of(dimension)));
        });

        Map<String, List<String>> cappedRelationships = new HashMap<>();
        boundedRelationships.forEach((id, values) -> cappedRelationships.put(id, values.stream().limit(8).toList()));
        List<EvaluationDtos.GraphFact> facts = new ArrayList<>();
        for (String id : selected) {
            JsonNode node = nodes.get(id);
            if (node == null) continue;
            Symbol symbol = symbol(node, repositoryUrl);
            facts.add(new EvaluationDtos.GraphFact(id, dimension,
                    limit(text(node, "label"), 160), limit(text(node, "type"), 80),
                    limit(description(node, symbol), MAX_DESCRIPTION), symbol.filePath(),
                    cappedRelationships.getOrDefault(id, List.of()), List.of(dimension),
                    symbol.name(), symbol.type(), symbol.language(), symbol.repository(), symbol.line(), symbol.scoreable()));
        }
        Set<String> keptRelations = new HashSet<>();
        cappedRelationships.forEach((id, labels) -> labels.forEach(label -> keptRelations.add(id + "\u0000" + label)));
        List<EvaluationDtos.GraphEdge> boundedEdges = edges.stream().filter(edge ->
                keptRelations.contains(edge.sourceId() + "\u0000" + (edge.label() + " → " + text(nodes.get(edge.targetId()), "label")))
                        || keptRelations.contains(edge.targetId() + "\u0000" + (text(nodes.get(edge.sourceId()), "label") + " → " + edge.label())))
                .map(edge -> new EvaluationDtos.GraphEdge(edge.sourceId(), edge.targetId(), edge.label(), edge.dimensions()))
                .toList();
        return new Expansion(List.copyOf(facts), boundedEdges);
    }

    record Expansion(List<EvaluationDtos.GraphFact> facts, List<EvaluationDtos.GraphEdge> edges) { }

    private static int score(JsonNode node, Set<String> queryTerms, String searchText) {
        Symbol symbol = symbol(node, null);
        String name = symbol.name().toLowerCase(Locale.ROOT);
        String normalizedSearch = searchText.toLowerCase(Locale.ROOT);
        int score = !name.isBlank() && normalizedSearch.contains(name) ? 30 : 0;
        Set<String> nodeTerms = terms(node.toString() + " " + name);
        for (String term : queryTerms) if (!GENERIC_TERMS.contains(term) && nodeTerms.contains(term)) score++;
        return score;
    }

    private static boolean isScoreableSymbol(JsonNode node) {
        Symbol symbol = symbol(node, null);
        return symbol.scoreable();
    }

    private static Symbol symbol(JsonNode node, String repositoryUrl) {
        JsonNode properties = parsed(node == null ? null : node.path("properties"));
        String rawPath = firstText(properties, "file_path", "filePath", "path", "source_uri");
        if (rawPath == null) rawPath = findPath(node);
        String path = normalizePath(rawPath, repositoryUrl);
        String language = firstText(properties, "language", "lang");
        String repository = firstText(properties, "repo", "repository", "repository_name");
        String name = firstText(properties, "symbol_name", "symbolName", "name", "title", "qualified_name");
        String label = text(node, "label");
        if (name == null) name = symbolFromLabel(label);
        String nodeType = text(node, "type");
        String kind = firstText(properties, "symbol_kind", "symbolKind", "kind");
        String symbolType = kind != null ? kind : nodeType;
        Integer line = firstInteger(properties, "line", "start_line", "startLine", "line_number", "lineNumber");
        boolean codeNode = isCodeNode(nodeType, kind, language);
        boolean scoreable = codeNode && path != null && name != null && !name.isBlank() && isCodePath(path)
                && (language == null || !language.equalsIgnoreCase("markdown"))
                && (repositoryUrl == null || repositoryMatches(repository, repositoryUrl));
        return new Symbol(name == null ? "" : name, symbolType == null ? "" : symbolType,
                path, language, repository, line, scoreable);
    }

    private static String description(JsonNode node, Symbol symbol) {
        JsonNode properties = parsed(node.path("properties"));
        String description = firstText(properties, "description", "summary", "docstring");
        if (description != null) return description;
        String modifiers = firstText(properties, "modifiers", "visibility", "exported");
        return String.join("; ", List.of(symbol.name(), symbol.type(),
                modifiers == null ? "" : modifiers, symbol.language() == null ? "" : symbol.language()))
                .replaceAll("(?:; )+", "; ").replaceAll("^; |; $", "");
    }

    private static boolean belongsToRepository(JsonNode node, String repositoryUrl) {
        if (repositoryUrl == null) return true;
        Symbol symbol = symbol(node, repositoryUrl);
        String repo = symbol.repository();
        return repo == null || repositoryMatches(repo, repositoryUrl)
                || (symbol.filePath() != null && symbol.scoreable());
    }

    private static boolean repositoryMatches(String repository, String repositoryUrl) {
        if (repository == null || repository.isBlank()) return true;
        try {
            String[] parts = URI.create(repositoryUrl).getPath().replaceAll("/$", "").split("/");
            String owner = parts.length > 1 ? parts[1] : "";
            String name = parts.length > 2 ? parts[2].replaceAll("(?i)\\.git$", "") : "";
            String value = repository.trim().toLowerCase(Locale.ROOT).replaceAll("\\.git$", "");
            return value.equals(name.toLowerCase(Locale.ROOT))
                    || value.equals((owner + "/" + name).toLowerCase(Locale.ROOT));
        } catch (RuntimeException ignored) { return false; }
    }

    private static boolean isCodePath(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        int dot = value.lastIndexOf('.');
        return dot >= 0 && CODE_EXTENSIONS.contains(value.substring(dot));
    }

    private static boolean isCodeNode(String nodeType, String symbolKind, String language) {
        if (language != null && !language.isBlank() && !language.equalsIgnoreCase("markdown")) return true;
        String kind = ((nodeType == null ? "" : nodeType) + " " +
                (symbolKind == null ? "" : symbolKind)).toLowerCase(Locale.ROOT);
        return List.of("code", "symbol", "module", "class", "method", "function", "test")
                .stream().anyMatch(kind::contains);
    }

    private static String normalizePath(String raw, String repositoryUrl) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.replace('\\', '/');
        try { if (value.startsWith("file:")) value = URI.create(value).getPath(); }
        catch (RuntimeException ignored) { return null; }
        if (value == null) return null;
        value = value.replace('\\', '/');
        if (value.matches("^[A-Za-z]:/.*") || value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*")) return null;
        if (repositoryUrl != null) try {
            String[] parts = URI.create(repositoryUrl).getPath().replaceAll("/$", "").split("/");
            String directory = "github.com-" + parts[1] + "-" + parts[2].replaceAll("(?i)\\.git$", "");
            int root = value.toLowerCase(Locale.ROOT).indexOf(directory.toLowerCase(Locale.ROOT));
            if (root >= 0) {
                value = value.substring(root + directory.length()).replaceFirst("^/", "");
            } else if (value.startsWith("/")) return null;
        } catch (RuntimeException ignored) { return null; }
        while (value.startsWith("/")) value = value.substring(1);
        if (value.isBlank() || value.contains("../") || value.equals("..")) return null;
        return value;
    }

    private static String symbolFromLabel(String label) {
        if (label == null || label.isBlank()) return null;
        String name = label;
        int hash = name.lastIndexOf('#');
        if (hash >= 0 && hash < name.length() - 1) name = name.substring(hash + 1);
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        return name.isBlank() ? null : name;
    }

    private static String flatten(Object value) {
        if (value == null) return "";
        JsonNode root = new ObjectMapper().valueToTree(value);
        if (root.isTextual()) try { root = new ObjectMapper().readTree(root.asText()); }
        catch (Exception ignored) { }
        StringBuilder result = new StringBuilder();
        flatten(root, result);
        return result.toString();
    }

    private static void flatten(JsonNode node, StringBuilder result) {
        if (node == null || node.isNull()) return;
        if (node.isTextual() || node.isNumber()) result.append(' ').append(node.asText());
        else if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) flatten(fields.next().getValue(), result);
        } else if (node.isArray()) node.forEach(child -> flatten(child, result));
    }

    private static Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        var matcher = WORD.matcher(text == null ? "" : text);
        while (matcher.find()) result.add(matcher.group().toLowerCase(Locale.ROOT));
        return result;
    }

    private static JsonNode parsed(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return new ObjectMapper().createObjectNode();
        if (value.isTextual()) try { return new ObjectMapper().readTree(value.asText()); }
        catch (Exception ignored) { }
        return value;
    }

    private static String findPath(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) {
            JsonNode parsed = parsed(node);
            return parsed == node ? null : findPath(parsed);
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT);
                JsonNode value = field.getValue();
                if ((key.contains("path") || key.contains("file") || key.contains("source_uri")) && value.isTextual())
                    return value.asText();
                String nested = findPath(value);
                if (nested != null) return nested;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = findPath(child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) return null;
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isValueNode() && !value.isNull() && !value.asText().isBlank()) return value.asText();
        }
        return null;
    }

    private static Integer firstInteger(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) return null;
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.canConvertToInt() && value.asInt() > 0) return value.asInt();
        }
        return null;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isValueNode() && !value.isNull() ? value.asText() : null;
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private record Symbol(String name, String type, String filePath, String language,
                          String repository, Integer line, boolean scoreable) { }
    private record DepthNode(String id, int depth) { }
}
