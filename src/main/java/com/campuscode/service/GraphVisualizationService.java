package com.campuscode.service;

import com.campuscode.api.IngestionStatus;
import com.campuscode.persistence.IngestionJobEntity;
import com.campuscode.persistence.IngestionJobRepository;
import com.campuscode.repository.RepositoryUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class GraphVisualizationService {
    private static final Logger log = LoggerFactory.getLogger(GraphVisualizationService.class);

    private final RestClient cognee;
    private final IngestionJobRepository ingestions;
    private final CogneeDatasetResolver datasetResolver;

    public GraphVisualizationService(RestClient cognee, IngestionJobRepository ingestions,
                                     CogneeDatasetResolver datasetResolver) {
        this.cognee = cognee;
        this.ingestions = ingestions;
        this.datasetResolver = datasetResolver;
    }

    public String visualizeRepository(String repositoryUrl) {
        RepositoryUrl repository = RepositoryUrl.parse(repositoryUrl);
        List<IngestionJobEntity> jobs = ingestions.findByRepositoryUrlOrderByCreatedAtDesc(repository.canonicalUrl());
        if (jobs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No ingestion exists for this repository. Submit it for ingestion first.");
        }

        IngestionJobEntity latest = jobs.getFirst();
        if (latest.getStatus() != IngestionStatus.State.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    latest.getStatus() == IngestionStatus.State.FAILED
                            ? "The latest repository ingestion failed. Retry ingestion before opening its graph."
                            : "Repository ingestion is still in progress. Try again after it completes.");
        }

        String datasetId = latest.getCogneeDatasetId();
        if (datasetId == null || datasetId.isBlank()) {
            datasetId = datasetResolver.resolveId(latest.getDatasetName());
            latest.setCogneeDatasetId(datasetId);
            ingestions.save(latest);
            log.info("Resolved Cognee dataset for graph visualization ingestionId={} dataset={} datasetId={}",
                    latest.getId(), latest.getDatasetName(), datasetId);
        }

        String resolvedDatasetId = datasetId;
        log.info("Cognee graph visualization started ingestionId={} datasetId={}", latest.getId(), resolvedDatasetId);
        String html = cognee.get()
                .uri(builder -> builder.path("/api/v1/visualize")
                        .queryParam("dataset_id", resolvedDatasetId)
                        .queryParam("full", true)
                        .build())
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);
        if (html == null || html.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Cognee returned an empty graph visualization.");
        }
        log.info("Cognee graph visualization completed ingestionId={} datasetId={} htmlBytes={}",
                latest.getId(), resolvedDatasetId, html.length());
        return html;
    }
}
