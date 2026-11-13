package com.campuscode.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableConfigurationProperties(CogneeProperties.class)
public class HttpClientConfig {
    @Bean
    RestClient cogneeRestClient(CogneeProperties properties, CogneeTokenProvider tokenProvider) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokenProvider.getToken());
                    return execution.execute(request, body);
                })
                .build();
    }

    @Bean
    ExecutorService ingestionExecutor(@Value("${campuscode.worker.threads:2}") int threads) {
        return Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "campuscode-worker");
            thread.setDaemon(true);
            return thread;
        });
    }
}
