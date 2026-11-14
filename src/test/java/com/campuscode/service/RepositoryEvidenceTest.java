package com.campuscode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryEvidenceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void extractsTextAndSourcePathFromCogneeChunk() {
        var response = List.of(Map.of(
                "text", "public class Application { public static void main(String[] args) {} }",
                "metadata", Map.of("file_path", "src/main/java/Application.java")));

        List<RepositoryEvidence> evidence = RepositoryEvidence.extract(response, mapper,
                "https://github.com/example/project");

        assertThat(evidence).hasSize(1);
        assertThat(evidence.getFirst().id()).isEqualTo("e1");
        assertThat(evidence.getFirst().filePath()).isEqualTo("src/main/java/Application.java");
        assertThat(evidence.getFirst().text()).contains("public class Application");
    }

    @Test
    void doesNotTreatCogneeConversationalPlaceholderAsRepositoryEvidence() {
        List<RepositoryEvidence> evidence = RepositoryEvidence.extract(List.of("Got it."), mapper,
                "https://github.com/example/project");

        assertThat(evidence).isEmpty();
    }

    @Test
    void unwrapsCogneeExternalMetadataAndConvertsItsClonePathToRepoRelativePath() {
        var response = List.of(Map.of(
                "text", "public class SearchController { }",
                "external_metadata", "{\"_cognee\":{\"source_uri\":\"file:///app/.cognee/repos/github.com-example-project/src/main/java/SearchController.java\"}}"));

        List<RepositoryEvidence> evidence = RepositoryEvidence.extract(response, mapper,
                "https://github.com/example/project");

        assertThat(evidence).hasSize(1);
        assertThat(evidence.getFirst().filePath()).isEqualTo("src/main/java/SearchController.java");
    }

    @Test
    void preservesCogneeSourceLineMetadataForCitationValidation() {
        var response = List.of(Map.of(
                "excerpt", "public class SearchController {\n}",
                "filePath", "src/main/java/SearchController.java",
                "startLine", 18,
                "endLine", 19));

        List<RepositoryEvidence> evidence = RepositoryEvidence.extract(response, mapper,
                "https://github.com/example/project");

        assertThat(evidence).singleElement().satisfies(item -> {
            assertThat(item.startLine()).isEqualTo(18);
            assertThat(item.endLine()).isEqualTo(19);
        });
    }

    @Test
    void rejectsCogneePathsThatClearlyBelongToAnotherRepository() {
        var response = List.of(Map.of(
                "text", "public class ForeignController { }",
                "metadata", Map.of("file_path", "file:///app/.cognee/repos/github.com-other-foreign/src/ForeignController.java")));

        assertThat(RepositoryEvidence.extract(response, mapper, "https://github.com/example/project")).isEmpty();
    }

    @Test
    void auditPreservesRawPathAndExplainsMissingPathAndLineMetadata() {
        var response = List.of(
                Map.of("text", "class MissingPath {}", "startLine", 1, "endLine", 1),
                Map.of("text", "class MissingLines {}", "filePath", "src/MissingLines.java"),
                Map.of("text", "class ForeignPath {}", "filePath", "file:///app/.cognee/repos/github.com-other-repo/src/Foreign.java",
                        "startLine", 1, "endLine", 1));

        var extraction = RepositoryEvidence.extractWithAudit(response, mapper, "https://github.com/example/project");

        assertThat(extraction.candidates()).hasSize(3);
        assertThat(extraction.candidates()).extracting(RepositoryEvidence.RawCandidate::discardReason)
                .containsExactly("MISSING_REPOSITORY_PATH", "MISSING_OR_INVALID_LINE_RANGE", "OFF_REPOSITORY_PATH");
        assertThat(extraction.candidates().get(2).originalPath()).contains("github.com-other-repo");
        assertThat(extraction.evidence()).singleElement().satisfies(item -> {
            assertThat(item.filePath()).isEqualTo("src/MissingLines.java");
            assertThat(item.startLine()).isZero();
        });
    }

    @Test
    void codeSearchStructuredJsonIsSummarizedAsGraphDataRatherThanPassages() {
        String response = "{\"citations\":[{\"file\":\"src/main/App.java\",\"line\":12,\"node_id\":\"node-1\"}],"
                + "\"neighborhood\":{\"nodes\":[{\"id\":\"node-1\"},{\"id\":\"node-2\"}],"
                + "\"edges\":[{\"source_id\":\"node-1\",\"target_id\":\"node-2\"}]}}";

        var summary = RepositoryEvidence.summarizeCodeSearch(response, mapper);
        var passageExtraction = RepositoryEvidence.extractWithAudit(response, mapper,
                "https://github.com/example/project");

        assertThat(summary.seedNodeIds()).containsExactly("node-1");
        assertThat(summary.resultPaths()).containsExactly("src/main/App.java");
        assertThat(summary.neighborhoodNodeCount()).isEqualTo(2);
        assertThat(summary.neighborhoodEdgeCount()).isEqualTo(1);
        assertThat(passageExtraction.candidates()).isEmpty();
        assertThat(passageExtraction.evidence()).isEmpty();
    }

    @Test
    void chunkParserDoesNotTreatBareMetadataStringsAsPassageCandidates() {
        var response = List.of(Map.of("metadata", Map.of("file_path", "src/App.java", "id", "a-long-metadata-id")));

        var extraction = RepositoryEvidence.extractWithAudit(response, mapper,
                "https://github.com/example/project");

        assertThat(extraction.candidates()).isEmpty();
        assertThat(extraction.evidence()).isEmpty();
    }
}
