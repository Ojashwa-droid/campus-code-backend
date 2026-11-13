package com.campuscode.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Service
public class CogneeDatasetResolver {
    private final RestClient cognee;

    public CogneeDatasetResolver(RestClient cognee) {
        this.cognee = cognee;
    }

    public String resolveId(String datasetName) {
        JsonNode datasets = cognee.get().uri("/api/v1/datasets").retrieve().body(JsonNode.class);
        List<String> matches = new ArrayList<>();
        collectMatches(datasets, datasetName, matches);
        if (matches.isEmpty()) {
            throw new IllegalStateException("Cognee dataset was not found for this repository. Verify ingestion completed successfully.");
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("Cognee returned multiple datasets with the same repository dataset name.");
        }
        return matches.getFirst();
    }

    private static void collectMatches(JsonNode node, String datasetName, List<String> matches) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(child -> collectMatches(child, datasetName, matches));
            return;
        }
        if (!node.isObject()) return;

        String name = text(node, "name", "dataset_name", "datasetName");
        String id = text(node, "id", "dataset_id", "datasetId");
        if (datasetName.equals(name) && id != null) matches.add(id);
        node.fields().forEachRemaining(entry -> collectMatches(entry.getValue(), datasetName, matches));
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isTextual() && !value.textValue().isBlank()) return value.textValue();
        }
        return null;
    }
}
