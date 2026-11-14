package com.campuscode.service;

import com.campuscode.api.EvaluationDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationContextBuilderTest {
    private static final String REPOSITORY = "https://github.com/example/repo";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void deduplicatesPassagesAndGraphNodesButPreservesEveryDimensionTag() throws Exception {
        var passage = new RepositoryEvidence("e1", "src/main/App.java", "class App {\n  void run() {}\n}", 1, 3);
        var shared = new EvaluationDtos.RepositoryEvidenceItem("e1", "architecture", passage.filePath(), 1, 3,
                passage.text(), List.of("architecture", "maintainability"));
        var graph = List.of(
                new EvaluationDtos.GraphFact("n1", "architecture", "App", "CodeSymbol", "entry point", passage.filePath(), List.of(), List.of("architecture")),
                new EvaluationDtos.GraphFact("n1", "maintainability", "App", "CodeSymbol", "entry point", passage.filePath(), List.of(), List.of("maintainability")));
        var input = bundle(List.of(shared, shared), graph);

        var result = builder(40000, 4, 4000, 36, 500).build(input, List.of(passage), "system prompt", REPOSITORY);

        assertThat(result.bundle().evidence()).hasSize(1);
        assertThat(result.bundle().evidence().getFirst().dimensions()).containsExactly("architecture", "maintainability");
        assertThat(result.bundle().graphContext()).hasSize(1);
        assertThat(result.bundle().graphContext().getFirst().dimensions()).containsExactly("architecture", "maintainability");
        assertThat(result.json()).isEqualTo(mapper.writeValueAsString(result.bundle()));
        assertThat(result.evidence()).containsExactly(passage);
    }

    @Test
    void selectsCodeBeforeDocumentationAndBalancesEvidenceByDimension() {
        var code = new RepositoryEvidence("e1", "src/main/App.java", "class App {\n  void run() {}\n}", 1, 3);
        var docs = new RepositoryEvidence("e2", "README.md", "The application has a component called App.", 1, 1);
        var tests = new RepositoryEvidence("e3", "src/test/AppTest.java", "class AppTest {\n  void testRun() {}\n}", 1, 3);
        var items = List.of(item(code, "architecture"), item(docs, "architecture"), item(tests, "testing"));
        var result = builder(40000, 4, 4000, 36, 500).build(bundle(items, List.of()),
                List.of(code, docs, tests), "system prompt", REPOSITORY);

        assertThat(result.bundle().evidence()).extracting(EvaluationDtos.RepositoryEvidenceItem::evidenceId)
                .contains("e1", "e2", "e3");
        assertThat(result.evidencePerDimension()).containsEntry("architecture", 2).containsEntry("testing", 1);
        assertThat(result.sourceCodePassages()).isEqualTo(2);
        assertThat(result.documentationPassages()).isEqualTo(1);
    }

    @Test
    void enforcesEstimatedTokenCeilingAndTrimsLargeExcerptsAndGraphDescriptions() {
        String sourceText = "line\n".repeat(1000);
        var passage = new RepositoryEvidence("e1", "src/main/App.java", sourceText, 1, 1000);
        var item = item(passage, "architecture");
        var fact = new EvaluationDtos.GraphFact("n1", "architecture", "App", "CodeSymbol",
                "details ".repeat(1000), passage.filePath(), List.of("calls"));
        int budget = 1200;

        var result = builder(budget, 4, 800, 36, 500).build(bundle(List.of(item), List.of(fact)),
                List.of(passage), "system prompt", REPOSITORY);

        assertThat(result.estimatedTokens()).isLessThanOrEqualTo(budget);
        assertThat(result.bundle().evidence()).allSatisfy(evidence -> assertThat(evidence.excerpt().length()).isLessThanOrEqualTo(800));
        assertThat(result.bundle().graphContext()).allSatisfy(graph -> assertThat(graph.description().length()).isLessThanOrEqualTo(501));
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void dropsEvidenceWithoutVerifiablePathAndLineRange() {
        var untraceable = new RepositoryEvidence("e1", "src/App.java", "class App {}", 0, 0);
        var item = new EvaluationDtos.RepositoryEvidenceItem("e1", "architecture", "src/App.java", 0, 0,
                untraceable.text());

        var result = builder(40000, 4, 4000, 36, 500).build(bundle(List.of(item), List.of()),
                List.of(untraceable), "system prompt", REPOSITORY);

        assertThat(result.bundle().evidence()).isEmpty();
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void prioritizesSourceCodeWhenBudgetOnlyFitsOnePassage() {
        var code = new RepositoryEvidence("e1", "src/main/App.java", "class App {\n  void run() {}\n}", 1, 3);
        var docs = new RepositoryEvidence("e2", "README.md", "Project documentation explains the application architecture.", 1, 1);

        var result = builder(220, 4, 4000, 0, 500).build(
                bundle(List.of(item(docs, "architecture"), item(code, "architecture")), List.of()),
                List.of(docs, code), "system prompt", REPOSITORY);

        assertThat(result.bundle().evidence()).extracting(EvaluationDtos.RepositoryEvidenceItem::evidenceId)
                .containsExactly("e1");
    }

    @Test
    void givesEachDimensionEvidenceBeforeFillingMoreFromOneDimension() {
        var architecture = List.of(
                new RepositoryEvidence("a1", "src/main/A.java", "class A { void one() {} }", 1, 1),
                new RepositoryEvidence("a2", "src/main/B.java", "class B { void two() {} }", 1, 1),
                new RepositoryEvidence("a3", "src/main/C.java", "class C { void three() {} }", 1, 1),
                new RepositoryEvidence("a4", "src/main/D.java", "class D { void four() {} }", 1, 1));
        var testing = new RepositoryEvidence("t1", "src/test/AppTest.java", "class AppTest { void test() {} }", 1, 1);
        var items = new java.util.ArrayList<EvaluationDtos.RepositoryEvidenceItem>();
        architecture.forEach(value -> items.add(item(value, "architecture")));
        items.add(item(testing, "testing"));

        var result = builder(300, 4, 4000, 0, 500).build(
                bundle(items, List.of()), java.util.stream.Stream.concat(architecture.stream(), java.util.stream.Stream.of(testing)).toList(),
                "system prompt", REPOSITORY);

        assertThat(result.evidencePerDimension().get("architecture")).isGreaterThan(0);
        assertThat(result.evidencePerDimension().get("testing")).isEqualTo(1);
        assertThat(result.bundle().evidence()).hasSizeLessThan(5);
    }

    @Test
    void marksCodeTestsAndDocumentationInPreviewEvidence() {
        var source = new RepositoryEvidence("s", "src/main/App.java", "class App {}", 1, 1);
        var test = new RepositoryEvidence("t", "src/test/AppTest.java", "class AppTest {}", 1, 1);
        var docs = new RepositoryEvidence("d", "README.md", "Application overview", 1, 1);
        var items = List.of(item(source, "architecture"), item(test, "testing"), item(docs, "architecture"));

        var result = builder(40000, 4, 4000, 0, 500).build(bundle(items, List.of()),
                List.of(source, test, docs), "system prompt", REPOSITORY);

        assertThat(result.bundle().evidence()).extracting(EvaluationDtos.RepositoryEvidenceItem::evidenceKind)
                .containsExactly("SOURCE_CODE", "TEST_CODE", "SUPPORTING_DOCUMENTATION");
    }

    private EvaluationContextBuilder builder(int tokens, int perDimension, int evidenceChars, int graphFacts, int graphChars) {
        return new EvaluationContextBuilder(mapper, tokens, 3, perDimension, evidenceChars, graphFacts, graphChars);
    }

    private static EvaluationDtos.RepositoryEvidenceItem item(RepositoryEvidence evidence, String dimension) {
        return new EvaluationDtos.RepositoryEvidenceItem(evidence.id(), dimension, evidence.filePath(),
                evidence.startLine(), evidence.endLine(), evidence.text());
    }

    private static EvaluationDtos.EvidenceBundle bundle(List<EvaluationDtos.RepositoryEvidenceItem> evidence,
                                                         List<EvaluationDtos.GraphFact> facts) {
        return new EvaluationDtos.EvidenceBundle(UUID.randomUUID(), UUID.randomUUID(), REPOSITORY, evidence, facts);
    }
}
