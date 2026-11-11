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

record RepositoryEvidence(String id, String filePath, String text) {
    private static final Pattern SOURCE_PATH = Pattern.compile("(?i)(?:source|file_path|filepath|file|path)\\s*[:=]\\s*['\\\"]?([^\\s'\\\"]+)");

    static List<RepositoryEvidence> extract(Object response, ObjectMapper mapper, String repositoryUrl) {
        JsonNode root = mapper.valueToTree(response);
        if (root.isTextual()) {
            try { root = mapper.readTree(root.textValue()); }
            catch (Exception ignored) { }
        }
        List<RepositoryEvidence> evidence = new ArrayList<>();
        collect(root, evidence, repositoryUrl);
        return evidence.stream().filter(item -> !item.text().isBlank()).distinct().toList();
    }

    private static void collect(JsonNode node, List<RepositoryEvidence> out, String repositoryUrl) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(child -> collect(child, out, repositoryUrl));
            return;
        }
        if (node.isObject()) {
            String content = firstText(node, "text", "chunk", "content", "page_content", "document");
            if (content != null && !content.isBlank()) {
                JsonNode metadata = node.path("metadata");
                String filePath = findPath(metadata);
                if (filePath == null) filePath = findPath(node);
                filePath = normalizePath(filePath, repositoryUrl);
                if (filePath == null) filePath = pathFromText(content);
                out.add(new RepositoryEvidence("e" + (out.size() + 1), filePath, content));
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) collect(fields.next().getValue(), out, repositoryUrl);
            return;
        }
        if (node.isTextual() && node.textValue().length() > 20) {
            String content = node.textValue();
            out.add(new RepositoryEvidence("e" + (out.size() + 1), pathFromText(content), content));
        }
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
