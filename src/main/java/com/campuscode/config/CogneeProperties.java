package com.campuscode.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "campuscode.cognee")
public record CogneeProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
