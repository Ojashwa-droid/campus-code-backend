package com.campuscode.service;

import com.campuscode.repository.RepositoryUrl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Resolves GitHub's default branch for citation links; source contents are never fetched. */
final class GitHubRepositoryMetadataClient {
    private static final Logger log = LoggerFactory.getLogger(GitHubRepositoryMetadataClient.class);
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ConcurrentMap<String, Optional<String>> branchCache = new ConcurrentHashMap<>();

    GitHubRepositoryMetadataClient(ObjectMapper mapper) { this.mapper = mapper; }

    String defaultBranch(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) return null;
        return branchCache.computeIfAbsent(repositoryUrl, this::loadDefaultBranch).orElse(null);
    }

    private Optional<String> loadDefaultBranch(String repositoryUrl) {
        try {
            URI repo = URI.create(RepositoryUrl.parse(repositoryUrl).canonicalUrl());
            String[] parts = repo.getPath().replaceAll("/$", "").split("/");
            URI api = URI.create("https://api.github.com/repos/" + parts[1] + "/" + parts[2]);
            HttpRequest request = HttpRequest.newBuilder(api).timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "CampusCode")
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Could not resolve GitHub default branch repository={} httpStatus={}", repositoryUrl, response.statusCode());
                return Optional.empty();
            }
            JsonNode payload = mapper.readTree(response.body());
            String branch = payload.path("default_branch").asText(null);
            return branch == null || branch.isBlank() ? Optional.empty() : Optional.of(branch);
        } catch (Exception e) {
            log.warn("Could not resolve GitHub default branch repository={} error={}", repositoryUrl, e.getMessage());
            return Optional.empty();
        }
    }
}
