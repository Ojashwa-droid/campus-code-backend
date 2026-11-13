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
}
