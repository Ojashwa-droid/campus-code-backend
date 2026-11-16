package com.campuscode.service;

import com.campuscode.api.RepositoryChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryChatContextBuilderTest {
    @Test
    void promptIsBoundedAndKeepsOnlySixRecentTurns() {
        RepositoryChatContextBuilder builder = new RepositoryChatContextBuilder(12000, 3);
        List<RepositoryChatRequest.Turn> turns = IntStream.range(0, 20)
                .mapToObj(index -> new RepositoryChatRequest.Turn(index % 2 == 0
                        ? RepositoryChatRequest.Role.USER : RepositoryChatRequest.Role.ASSISTANT,
                        "turn-" + index)).toList();
        List<RepositoryChatContextBuilder.Source> evidence = IntStream.range(0, 24)
                .mapToObj(index -> new RepositoryChatContextBuilder.Source("P" + (index + 1),
                        "COGNEE_RETRIEVED_PASSAGE", "src/File" + index + ".java", null, null,
                        null, null, "x".repeat(3000))).toList();

        var built = builder.build("How does this work?", turns, evidence);

        assertThat(built.estimatedTokens()).isLessThanOrEqualTo(12000);
        assertThat(built.userPrompt()).contains("turn-8", "turn-19").doesNotContain("turn-7");
        assertThat(built.sources()).isNotEmpty();
        assertThat(built.userPrompt().length()).isLessThanOrEqualTo(12000 * 3);
    }

    @Test
    void promptRequiresGroundedReadableNonJsonAnswersWithoutCitations() {
        assertThat(RepositoryChatContextBuilder.SYSTEM_PROMPT)
                .contains("Never return JSON")
                .contains("Never include citations")
                .contains("Not finding evidence is not proof")
                .contains("keep it separate from what the repository currently implements")
                .contains("You are not a general-purpose assistant")
                .contains("Do not fulfill an out-of-scope request")
                .contains("If it is genuinely unclear whether a request concerns this repository");
        assertThat(RepositoryChatContextBuilder.sanitizeAnswer("Found in [P1] and [G9]."))
                .isEqualTo("Found in and .");
    }

    @Test
    void evidenceContextDoesNotExposeCitationKeysToTheModel() {
        var source = new RepositoryChatContextBuilder.Source("P1", "COGNEE_RETRIEVED_PASSAGE",
                "src/Example.java", null, null, null, null, "Example evidence showing how the repository component is implemented and connected to its supporting services.");
        var built = new RepositoryChatContextBuilder(12000, 3).build("What does it do?", List.of(), List.of(source));

        assertThat(built.userPrompt()).contains("src/Example.java", "Example evidence showing")
                .doesNotContain("[P1]");
    }

    @Test
    void cogneeSearchRequestsAreAlwaysScopedToTheStoredDatasetUuid() {
        String datasetId = "a42cd17e-e24a-5309-b4ea-a20310ad4606";
        var hybrid = RepositoryChatService.hybridSearchRequest(datasetId, "request flow");
        var code = RepositoryChatService.codeSearchRequest(datasetId, "request flow");

        assertThat(hybrid).containsEntry("dataset_ids", List.of(datasetId))
                .containsEntry("search_type", "HYBRID_COMPLETION")
                .containsEntry("only_context", true).containsEntry("include_references", true);
        assertThat(code).containsEntry("dataset_ids", List.of(datasetId))
                .containsEntry("search_type", "CODE").containsEntry("only_context", true)
                .containsEntry("code_query", java.util.Map.of("operation", "ask"));
    }
}
