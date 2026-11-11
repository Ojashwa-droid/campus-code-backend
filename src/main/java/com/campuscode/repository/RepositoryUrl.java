package com.campuscode.repository;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

public record RepositoryUrl(String canonicalUrl, String datasetName) {
    private static final Pattern PATH = Pattern.compile("^/[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+(?:\\.git)?/?$");

    public static RepositoryUrl parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("repositoryUrl is required");
        }
        final URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("repositoryUrl must be a valid GitHub repository URL");
        }
        String path = uri.getPath();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || path == null
                || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Only public https://github.com/{owner}/{repo} URLs are supported");
        }
        String normalizedPath = path.replaceAll("/$", "").replaceAll("\\.git$", "");
        String[] segments = normalizedPath.substring(1).split("/");
        if (segments[0].equals(".") || segments[0].equals("..")
                || segments[1].equals(".") || segments[1].equals("..")) {
            throw new IllegalArgumentException("Only public https://github.com/{owner}/{repo} URLs are supported");
        }
        String canonical = "https://github.com" + normalizedPath;
        return new RepositoryUrl(canonical, "repo_" + sha256(canonical).substring(0, 20));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
