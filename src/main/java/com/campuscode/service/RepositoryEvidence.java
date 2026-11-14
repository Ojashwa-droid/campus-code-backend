package com.campuscode.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.net.URI;
import java.util.LinkedHashSet;

record RepositoryEvidence(String id, String filePath, String text, int startLine, int endLine) {
    RepositoryEvidence(String id, String filePath, String text) { this(id, filePath, text, 0, 0); }
    private static final Pattern SOURCE_PATH = Pattern.compile("(?i)(?:source|file_path|filepath|file|path)\\s*[:=]\\s*['\\\"]?([^\\s'\\\"]+)");

    static List<RepositoryEvidence> extract(Object response, ObjectMapper mapper, String repositoryUrl) {
        return extractWithAudit(response, mapper, repositoryUrl).evidence();
    }

    static Extraction extractWithAudit(Object response, ObjectMapper mapper, String repositoryUrl) {
        JsonNode root = mapper.valueToTree(response);
        if (root.isTextual()) {
            try { root = mapper.readTree(root.textValue()); }
            catch (Exception ignored) { }
        }
        List<RepositoryEvidence> evidence = new ArrayList<>();
        List<RawCandidate> candidates = new ArrayList<>();
        collect(root, evidence, candidates, repositoryUrl);
        return new Extraction(evidence.stream().filter(item -> !item.text().isBlank() && item.filePath() != null)
                .distinct().toList(), List.copyOf(candidates));
    }

    static CodeSearchSummary summarizeCodeSearch(Object response, ObjectMapper mapper) {
        JsonNode root = mapper.valueToTree(response);
        if (root.isTextual()) root = parseJsonText(root, mapper);
        SetHolder ids = new SetHolder();
        SetHolder paths = new SetHolder();
        int[] shapeCounts = new int[2];
        collectCodeSearch(root, mapper, ids, paths, shapeCounts);
        return new CodeSearchSummary(ids.values(), paths.values(), shapeCounts[0], shapeCounts[1]);
    }

    private static void collectCodeSearch(JsonNode node, ObjectMapper mapper, SetHolder ids,
                                          SetHolder paths, int[] shapeCounts) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            JsonNode parsed = parseJsonText(node, mapper);
            if (parsed != node) collectCodeSearch(parsed, mapper, ids, paths, shapeCounts);
            return;
        }
        if (node.isArray()) { node.forEach(child -> collectCodeSearch(child, mapper, ids, paths, shapeCounts)); return; }
        if (!node.isObject()) return;
        JsonNode citations = node.get("citations");
        if (citations != null && citations.isArray()) citations.forEach(citation -> {
            String id = firstText(citation, "node_id", "nodeId", "id");
            String path = firstText(citation, "file", "file_path", "filePath", "path");
            ids.add(id);
            paths.add(path);
        });
        JsonNode neighborhood = node.get("neighborhood");
        if (neighborhood != null && neighborhood.isObject()) {
            JsonNode nodes = neighborhood.path("nodes");
            JsonNode edges = neighborhood.path("edges");
            if (nodes.isArray()) shapeCounts[0] += nodes.size();
            if (edges.isArray()) shapeCounts[1] += edges.size();
        }
        node.elements().forEachRemaining(child -> collectCodeSearch(child, mapper, ids, paths, shapeCounts));
    }

    private static JsonNode parseJsonText(JsonNode value, ObjectMapper mapper) {
        String text = value.asText().strip();
        if (!text.startsWith("{") && !text.startsWith("[")) return value;
        try { return mapper.readTree(text); }
        catch (Exception ignored) { return value; }
    }

    record CodeSearchSummary(List<String> seedNodeIds, List<String> resultPaths,
                             int neighborhoodNodeCount, int neighborhoodEdgeCount) { }

    private static void collect(JsonNode node, List<RepositoryEvidence> out, List<RawCandidate> candidates,
                                String repositoryUrl) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(child -> collect(child, out, candidates, repositoryUrl));
            return;
        }
        if (node.isObject()) {
            String content = firstText(node, "text", "chunk", "content", "page_content", "document");
            if (content == null) content = firstText(node, "excerpt", "snippet", "code");
            if (content != null && !content.isBlank()) {
                JsonNode metadata = node.path("metadata");
                String originalPath = findPath(metadata);
                if (originalPath == null) originalPath = findPath(node);
                String filePath = normalizePath(originalPath, repositoryUrl);
                if (filePath == null) filePath = normalizePath(pathFromText(content), repositoryUrl);
                int startLine = firstInteger(metadata, node, "start_line", "startLine", "line_start", "lineStart",
                        "line", "line_number", "lineNumber", "start_line_number");
                int endLine = firstInteger(metadata, node, "end_line", "endLine", "line_end", "lineEnd", "end_line_number");
                if (startLine > 0 && endLine <= 0) endLine = startLine + (int) content.lines().count() - 1;
                String reason = filePath == null ? (originalPath == null ? "MISSING_REPOSITORY_PATH" : "OFF_REPOSITORY_PATH")
                        : startLine < 1 || endLine < startLine ? "MISSING_OR_INVALID_LINE_RANGE" : null;
                candidates.add(new RawCandidate(filePath, startLine, endLine, content, reason, originalPath));
                if (filePath != null) out.add(new RepositoryEvidence("e" + (out.size() + 1), filePath, content, startLine, endLine));
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) collect(fields.next().getValue(), out, candidates, repositoryUrl);
            return;
        }
        if (node.isTextual()) {
            JsonNode parsed = parseJsonText(node, new ObjectMapper());
            if (parsed != node) collect(parsed, out, candidates, repositoryUrl);
        }
        // Bare text leaves are not passage records; they may be IDs, graph labels, or metadata.
    }

    private static int lineFromText(String text, String keyPattern) {
        var matcher = Pattern.compile("(?i)\\b" + keyPattern + "\\b\\s*[:=]\\s*['\"]?(\\d+)").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static int firstInteger(JsonNode first, JsonNode second, String... names) {
        for (JsonNode node : List.of(first, second)) {
            int value = findInteger(node, SetHolder.of(names));
            if (value > 0) return value;
        }
        return 0;
    }

    private static int findInteger(JsonNode node, java.util.Set<String> names) {
        if (node == null || node.isNull()) return 0;
        if (node.isTextual() && (node.asText().startsWith("{") || node.asText().startsWith("["))) {
            try { return findInteger(new ObjectMapper().readTree(node.asText()), names); }
            catch (Exception ignored) { return 0; }
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (names.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                    JsonNode value = entry.getValue();
                    if (value.canConvertToInt() && value.asInt() > 0) return value.asInt();
                    if (value.isTextual()) try {
                        int parsed = Integer.parseInt(value.asText());
                        if (parsed > 0) return parsed;
                    } catch (NumberFormatException ignored) { }
                }
                int nested = findInteger(entry.getValue(), names);
                if (nested > 0) return nested;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                int nested = findInteger(child, names);
                if (nested > 0) return nested;
            }
        }
        return 0;
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isTextual() && !value.textValue().isBlank()) return value.textValue();
        }
        return null;
    }

    private static String findPath(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) {
            String value = node.textValue();
            if (value.startsWith("{") || value.startsWith("[")) {
                try { return findPath(new ObjectMapper().readTree(value)); }
                catch (Exception ignored) { }
            }
            return null;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                JsonNode value = entry.getValue();
                if ((key.contains("source") || key.contains("file") || key.equals("path")) && value.isTextual()) {
                    String candidate = value.textValue();
                    if (candidate.contains("/") || candidate.contains("\\")) return candidate;
                }
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

    record RawCandidate(String filePath, int startLine, int endLine, String content, String discardReason,
                        String originalPath) {
        String preview(int maxChars) {
            if (content == null) return "";
            String compact = content.replaceAll("\\s+", " ").strip();
            return compact.length() <= maxChars ? compact : compact.substring(0, maxChars) + "…";
        }
    }
    record Extraction(List<RepositoryEvidence> evidence, List<RawCandidate> candidates) { }

    private static final class SetHolder {
        private final LinkedHashSet<String> values = new LinkedHashSet<>();
        void add(String value) { if (value != null && !value.isBlank()) values.add(value); }
        List<String> values() { return List.copyOf(values); }
        static java.util.Set<String> of(String... names) {
            java.util.Set<String> result = new LinkedHashSet<>();
            for (String name : names) result.add(name.toLowerCase(Locale.ROOT));
            return result;
        }
    }

    private static String normalizePath(String path, String repositoryUrl) {
        if (path == null) return null;
        String value = path.replace('\\', '/');
        try {
            if (value.startsWith("file:")) value = URI.create(value).getPath();
        } catch (IllegalArgumentException ignored) { }
        if (value == null) return null;
        value = value.replace('\\', '/');
        try {
            String[] repoParts = URI.create(repositoryUrl).getPath().replaceAll("/$", "").split("/");
            String repoDirectory = "github.com-" + repoParts[1] + "-" + repoParts[2].replaceAll("\\.git$", "");
            int repoIndex = value.toLowerCase(Locale.ROOT).indexOf(repoDirectory.toLowerCase(Locale.ROOT));
            if (repoIndex >= 0) {
                int relativeStart = repoIndex + repoDirectory.length();
                if (relativeStart < value.length() && value.charAt(relativeStart) == '/') relativeStart++;
                value = value.substring(relativeStart);
            } else if (value.toLowerCase(Locale.ROOT).contains("github.com-")) {
                return null;
            }
        } catch (RuntimeException ignored) { }
        while (value.startsWith("/")) value = value.substring(1);
        return value.isBlank() ? null : value;
    }

    private static String pathFromText(String text) {
        var matcher = SOURCE_PATH.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }
}
