package com.campuscode.service;

import com.campuscode.api.EvaluationDtos;
import com.campuscode.api.IngestionStatus;
import com.campuscode.persistence.EvaluationJobEntity;
import com.campuscode.persistence.EvaluationJobRepository;
import com.campuscode.persistence.IngestionJobEntity;
import com.campuscode.persistence.IngestionJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationGraphCitationTest {
    @Test
    void citationsComeFromScopedGraphIdsAndBehaviorDimensionsRemainInsufficient() {
        UUID ingestionId = UUID.randomUUID();
        String repository = "https://github.com/example/repo";
        IngestionJobEntity ingestion = new IngestionJobEntity(ingestionId, repository, "repo_example", null);
        ingestion.transition(IngestionStatus.State.COMPLETED, null);
        EvaluationJobEntity job = new EvaluationJobEntity(UUID.randomUUID(), ingestionId, repository);

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        EvaluationService service = new EvaluationService(mock(RestClient.class), builder,
                mock(IngestionJobRepository.class), mock(EvaluationJobRepository.class),
                new ObjectMapper().findAndRegisterModules(), mock(ExecutorService.class),
                mock(EvaluationStreamHub.class), mock(JavaMailSender.class), "", "",
                40000, 3, 4, 4000, 36, 500);

        List<EvaluationDtos.GraphFact> facts = List.of(
                fact("controller-id", "RepositoryController", "src/RepositoryController.java", "architecture", List.of("calls EvaluationService"), 18),
                fact("service-id", "EvaluationService", "src/EvaluationService.java", "architecture", List.of("called by RepositoryController"), null),
                fact("test-id", "EvaluationServiceTest", "src/test/java/EvaluationServiceTest.java", "testing", List.of(), 42),
                fact("wrong-dimension", "SecurityConfig", "src/SecurityConfig.java", "security", List.of(), 9));
        EvaluationDtos.ModelScorecard model = new EvaluationDtos.ModelScorecard("summary", List.of(
                dimension("architecture", 4, List.of("controller-id", "service-id")),
                dimension("maintainability", 3, List.of("controller-id", "service-id")),
                dimension("testing", 2, List.of("test-id")),
                dimension("reliability", 5, List.of("service-id")),
                dimension("security", 5, List.of("wrong-dimension")),
                dimension("performance/scalability", 5, List.of("service-id"))));

        EvaluationDtos.EvaluationReport report = service.validateAndBuild(job, ingestion, model, facts, "main");

        assertThat(report.citationMode()).isEqualTo("GRAPH_METADATA");
        assertThat(report.defaultBranch()).isEqualTo("main");
        EvaluationDtos.DimensionScore architecture = dimension(report, "architecture");
        assertThat(architecture.score()).isEqualTo(4);
        assertThat(architecture.citations()).extracting(EvaluationDtos.Citation::filePath)
                .containsExactly("src/RepositoryController.java", "src/EvaluationService.java");
        assertThat(architecture.citations()).allSatisfy(citation -> {
            assertThat(citation.referenceType()).isEqualTo("COGNEE_GRAPH_METADATA");
            assertThat(citation.excerpt()).isNull();
        });
        assertThat(dimension(report, "testing").score()).isEqualTo(2);
        assertThat(dimension(report, "reliability").score()).isZero();
        assertThat(dimension(report, "security").score()).isZero();
        assertThat(dimension(report, "performance/scalability").score()).isZero();
    }

    private static EvaluationDtos.ModelDimensionScore dimension(String name, int score, List<String> ids) {
        return new EvaluationDtos.ModelDimensionScore(name, score, "Graph evidence supports this structure.",
                List.of(), List.of(), ids);
    }

    private static EvaluationDtos.GraphFact fact(String id, String name, String path, String dimension,
                                                  List<String> relationships, Integer line) {
        return new EvaluationDtos.GraphFact(id, dimension, name, "CodeSymbol", name, path, relationships,
                List.of(dimension), name, "class", "java", "repo", line, true);
    }

    private static EvaluationDtos.DimensionScore dimension(EvaluationDtos.EvaluationReport report, String name) {
        return report.dimensions().stream().filter(item -> item.dimension().equals(name)).findFirst().orElseThrow();
    }
}
