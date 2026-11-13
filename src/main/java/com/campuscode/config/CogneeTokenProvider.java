package com.campuscode.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/** Obtains and caches the local Cognee bearer token used by backend-to-Cognee calls. */
@Component
public class CogneeTokenProvider {
    private final RestClient authClient;
    private final String username;
    private final String password;
    private volatile String token;
    private volatile Instant expiresAt = Instant.EPOCH;

    public CogneeTokenProvider(RestClient.Builder builder, CogneeProperties properties) {
        this.authClient = builder.clone().baseUrl(properties.baseUrl()).build();
        this.username = properties.username();
        this.password = properties.password();
    }

    public String getToken() {
        if (token != null && Instant.now().isBefore(expiresAt)) return token;
        synchronized (this) {
            if (token != null && Instant.now().isBefore(expiresAt)) return token;
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("username", username);
            form.add("password", password);
            JsonNode response = authClient.post().uri("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(JsonNode.class);
            String accessToken = response == null ? null : response.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("Cognee login returned no access token; check COGNEE_USERNAME and COGNEE_PASSWORD.");
            }
            long expiresIn = response.path("expires_in").asLong(1200);
            this.token = accessToken;
            this.expiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
            return accessToken;
        }
    }
}
