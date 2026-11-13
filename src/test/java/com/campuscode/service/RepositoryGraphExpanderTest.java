package com.campuscode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryGraphExpanderTest {
    @Test
    void expandsFromSourcePathSeedsIntoRelatedGraphNodes() throws Exception {
        var graph = new ObjectMapper().readTree("""
                {
                  "nodes": [
                    {"id":"controller","label":"RepositoryController","type":"CodeSymbol","properties":{"file_path":"src/RepositoryController.java","description":"entry point"}},
                    {"id":"service","label":"EvaluationService","type":"CodeSymbol","properties":{"file_path":"src/EvaluationService.java"}},
                    {"id":"unrelated","label":"RandomDataLoader","type":"CodeSymbol","properties":{"file_path":"src/RandomDataLoader.java"}}
                  ],
                  "edges": [
                    {"source":"controller","target":"service","label":"calls"}
                  ]
                }
                """);
        var evidence = List.of(new RepositoryEvidence("e1", "src/RepositoryController.java", "class RepositoryController {}"));

        var facts = RepositoryGraphExpander.expand(graph, "architecture",
                "application entry points and component responsibilities", evidence);

        assertThat(facts).extracting(fact -> fact.id()).contains("controller", "service");
        assertThat(facts).extracting(fact -> fact.id()).doesNotContain("unrelated");
        assertThat(facts).filteredOn(fact -> fact.id().equals("controller")).first()
                .satisfies(fact -> assertThat(fact.relationships()).anyMatch(value -> value.contains("calls")));
    }

    @Test
    void onlyRepositoryScopedCodeSymbolsAreScoreableAndSearchNamesSeedExpansion() throws Exception {
        var graph = new ObjectMapper().readTree("""
                {
                  "nodes": [
                    {"id":"controller","label":"RepositoryController","type":"CodeSymbol","properties":{"file_path":"src/RepositoryController.java","symbol_kind":"class","repo":"OmniSearch"}},
                    {"id":"service","label":"EvaluationService","type":"CodeSymbol","properties":{"file_path":"src/EvaluationService.java","symbol_kind":"class","repo":"OmniSearch","line":24}},
                    {"id":"readme","label":"README","type":"CodeSymbol","properties":{"file_path":"README.md","symbol_kind":"section","repo":"OmniSearch"}},
                    {"id":"other-repo","label":"SecurityConfig","type":"CodeSymbol","properties":{"file_path":"src/SecurityConfig.java","symbol_kind":"class","repo":"Agentic-RAG-System"}}
                  ],
                  "edges": [
                    {"source":"controller","target":"service","label":"calls"},
                    {"source":"controller","target":"readme","label":"described_by"}
                  ]
                }
                """);

        var facts = RepositoryGraphExpander.expand(graph, "architecture", "controller service structure",
                Map.of("result", "RepositoryController EvaluationService"), Map.of(),
                "https://github.com/Ojashwa-droid/OmniSearch");

        assertThat(facts).extracting(fact -> fact.id()).contains("controller", "service").doesNotContain("other-repo");
        assertThat(facts).filteredOn(fact -> fact.id().equals("controller")).first()
                .satisfies(fact -> assertThat(fact.scoreable()).isTrue());
        assertThat(facts).filteredOn(fact -> fact.id().equals("readme")).first()
                .satisfies(fact -> assertThat(fact.scoreable()).isFalse());
        assertThat(facts).filteredOn(fact -> fact.id().equals("service")).first()
                .satisfies(fact -> assertThat(fact.line()).isEqualTo(24));
    }

    @Test
    void detailedExpansionRetainsOnlyEdgesBetweenSelectedReportNodes() throws Exception {
        var graph = new ObjectMapper().readTree("""
                {
                  "nodes": [
                    {"id":"controller","label":"RepositoryController","type":"CodeSymbol","properties":{"file_path":"src/RepositoryController.java","symbol_kind":"class","repo":"OmniSearch"}},
                    {"id":"service","label":"EvaluationService","type":"CodeSymbol","properties":{"file_path":"src/EvaluationService.java","symbol_kind":"class","repo":"OmniSearch"}},
                    {"id":"unrelated","label":"RandomDataLoader","type":"CodeSymbol","properties":{"file_path":"src/RandomDataLoader.java","symbol_kind":"class","repo":"OmniSearch"}},
                    {"id":"orphan","label":"UnusedScheduler","type":"CodeSymbol","properties":{"file_path":"src/UnusedScheduler.java","symbol_kind":"class","repo":"OmniSearch"}}
                  ],
                  "edges": [
                    {"source":"controller","target":"service","label":"calls"},
                    {"source":"unrelated","target":"service","label":"uses"}
                  ]
                }
                """);

        var expansion = RepositoryGraphExpander.expandDetailed(graph, "architecture",
                "controller service structure", Map.of("result", "RepositoryController EvaluationService"), Map.of(),
                "https://github.com/Ojashwa-droid/OmniSearch");

        var selectedIds = expansion.facts().stream().map(fact -> fact.id()).collect(java.util.stream.Collectors.toSet());
        assertThat(selectedIds).contains("controller", "service", "unrelated").doesNotContain("orphan");
        assertThat(expansion.edges()).hasSize(2).allSatisfy(edge -> {
            assertThat(selectedIds).contains(edge.sourceId(), edge.targetId());
            assertThat(edge.dimensions()).containsExactly("architecture");
        });
        assertThat(expansion.edges()).anySatisfy(edge -> {
            assertThat(edge.sourceId()).isEqualTo("controller");
            assertThat(edge.targetId()).isEqualTo("service");
            assertThat(edge.label()).isEqualTo("calls");
        });
    }
}
