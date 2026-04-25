import static org.assertj.core.api.Assertions.assertThat

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class FlowRendererTest {

    private static FlowModel buildModel(String yamlBody) {
        def pipeline = new Yaml().load(yamlBody) as Map
        FlowModel.from(pipeline, 'com.example.generated')
    }

    // ── prerequisites() ─────────────────────────────────────────────────────

    @Test
    void prerequisites_record_has_all_fields() {
        def model = buildModel("""
            name: test
            prerequisites: [sessionId, reportText, userId]
            nodes: []
        """)

        def output = FlowRenderer.prerequisites(model)

        assertThat(output).contains("package com.example.generated;")
        assertThat(output).contains("public record TestPrerequisites(")
        assertThat(output).contains("String sessionId")
        assertThat(output).contains("String reportText")
        assertThat(output).contains("String userId")
        assertThat(output).contains("public static TestPrerequisites from(Map<String, String> map)")
        assertThat(output).contains("public Map<String, String> toMap()")
    }

    // ── resultRecord() ──────────────────────────────────────────────────────

    @Test
    void result_record_has_typed_fields_and_imports() {
        def model = buildModel("""
            name: test
            nodes:
              - id: analyze
                type: llm
                result: { type: com.example.AnalysisResult }
              - id: count
                type: llm
                result: { type: Integer }
        """)

        def output = FlowRenderer.resultRecord(model)

        assertThat(output).contains("public record TestResult(")
        assertThat(output).contains("AnalysisResult analyze")
        assertThat(output).contains("Integer count")
        assertThat(output).contains("import com.example.AnalysisResult;")
        // builtin Integer는 import 없음
        assertThat(output).doesNotContain("import java.lang.Integer")
    }

    // ── fieldExtractor() ────────────────────────────────────────────────────

    @Test
    void field_extractor_with_no_refs_throws_for_any_input() {
        def model = buildModel("""
            name: test
            nodes:
              - id: a
                type: llm
        """)

        def output = FlowRenderer.fieldExtractor(model)

        assertThat(output).contains("class TestFieldExtractor implements NodeOutputExtractor")
        assertThat(output).contains("throw new IllegalArgumentException")
        assertThat(output).contains("Unknown field reference")
    }

    @Test
    void field_extractor_with_refs_emits_switch_cases() {
        def model = buildModel("""
            name: test
            nodes:
              - id: analyze
                type: llm
                result: { type: com.example.AnalysisResult }
              - id: consumer
                type: llm
                inputs:
                  text: analyze.someField
                  other: analyze.anotherField
        """)

        def output = FlowRenderer.fieldExtractor(model)

        assertThat(output).contains("case \"analyze.someField\"")
        assertThat(output).contains("case \"analyze.anotherField\"")
        assertThat(output).contains("GSON.fromJson(output, AnalysisResult.class)")
        assertThat(output).contains("import com.google.gson.Gson;")
    }

    // ── llmMiddleware() ─────────────────────────────────────────────────────

    @Test
    void llm_middleware_implements_LlmExecutor_with_decorator() {
        def model = buildModel("""
            name: my-flow
            nodes: []
        """)

        def output = FlowRenderer.llmMiddleware(model)

        assertThat(output).contains("class MyFlowLlmMiddleware implements LlmExecutor")
        assertThat(output).contains("private final LlmExecutor delegate;")
        assertThat(output).contains("public LlmRawResponse execute(LlmRequest request)")
        assertThat(output).contains("protected LlmRequest beforeExecute(LlmRequest request)")
        assertThat(output).contains("public void setSessionId(UUID sessionId)")
    }

    // ── pipelineBase() — sub-renderer 통합 ─────────────────────────────────

    @Test
    void pipeline_base_has_class_skeleton() {
        def model = buildModel("""
            name: my-flow
            prerequisites: [x]
            nodes:
              - id: a
                type: llm
                action: analyze
        """)

        def output = FlowRenderer.pipelineBase(model)

        assertThat(output).contains("public abstract class MyFlowBase {")
        assertThat(output).contains("protected MyFlowBase(Executor executor)")
        // 클래스 본체 종료
        assertThat(output).endsWith("}\n")
    }

    @Test
    void pipeline_base_loads_only_llm_actions_into_renderer() {
        def model = buildModel("""
            name: test
            nodes:
              - id: a
                type: llm
                action: foo
              - id: b
                type: scatter
              - id: c
                type: template
                action: bar
        """)

        def output = FlowRenderer.pipelineBase(model)

        // foo, bar만 ClasspathPromptRenderer에 등록 (scatter는 제외)
        assertThat(output).contains("ClasspathPromptRenderer.forActions(\"foo\", \"bar\")")
    }

    @Test
    void pipeline_base_emits_extension_point_methods() {
        def model = buildModel("name: t\nnodes: []")
        def output = FlowRenderer.pipelineBase(model)

        // 4개 오버라이더블
        assertThat(output).contains("protected ApiKeySupplier apiKeySupplier()")
        assertThat(output).contains("protected RateLimiter rateLimiter()")
        assertThat(output).contains("protected LlmExecutor createLlmExecutor()")
        assertThat(output).contains("protected LlmExecutor createLlmMiddleware(LlmExecutor base)")
    }

    @Test
    void pipeline_base_emits_abstract_server_node_methods() {
        def model = buildModel("""
            name: test
            nodes:
              - id: chunk_splitter
                type: scatter
              - id: chunk_merger
                type: gather
              - id: codebase_summary
                type: transform
        """)

        def output = FlowRenderer.pipelineBase(model)

        assertThat(output).contains("protected abstract java.util.List<String> chunkSplitterScatter")
        assertThat(output).contains("protected abstract String chunkMergerGather")
        assertThat(output).contains("protected abstract String codebaseSummaryTransform")
    }

    @Test
    void pipeline_base_emits_hook_methods_only_for_hook_nodes() {
        def model = buildModel("""
            name: test
            nodes:
              - id: a
                type: llm
                hook: true
                result: { type: String }
              - id: b
                type: llm
                hook: false
                result: { type: String }
        """)

        def output = FlowRenderer.pipelineBase(model)

        assertThat(output).contains("protected void onA(")
        assertThat(output).doesNotContain("protected void onB(")
    }

    @Test
    void pipeline_base_isCriticalFailure_checks_critical_nodes_only() {
        def model = buildModel("""
            name: test
            nodes:
              - id: c1
                type: llm
                critical: true
                result: { type: String }
              - id: c2
                type: llm
                critical: false
                result: { type: String }
        """)

        def output = FlowRenderer.pipelineBase(model)

        // critical=true인 c1만 isCriticalFailure 검사 대상
        assertThat(output).contains("if (result.c1() == null) return true;")
        assertThat(output).doesNotContain("if (result.c2() == null) return true;")
    }

    @Test
    void pipeline_base_no_critical_nodes_isCriticalFailure_always_false() {
        def model = buildModel("""
            name: test
            nodes:
              - id: a
                type: llm
                result: { type: String }
        """)

        def output = FlowRenderer.pipelineBase(model)

        assertThat(output).contains("public final boolean isCriticalFailure(TestResult result)")
        // critical 없으니 false만 반환
        assertThat(output).contains("return false;")
    }

    @Test
    void pipeline_base_imports_cover_extension_dependencies() {
        def model = buildModel("name: t\nnodes: []")
        def output = FlowRenderer.pipelineBase(model)

        // retry/rate limit/router/codegen이 정상 import되는지
        assertThat(output).contains("import com.returney.flow.application.InternalLlmRouter;")
        assertThat(output).contains("import com.returney.flow.adapter.common.SlidingWindowRateLimiter;")
        assertThat(output).contains("import com.returney.flow.adapter.parser.ProvidersConfig;")
        assertThat(output).contains("import com.returney.flow.adapter.parser.ProvidersYamlParser;")
        assertThat(output).contains("import com.returney.flow.port.RateLimiter;")
        assertThat(output).contains("import com.returney.flow.port.ApiKeySupplier;")
    }

    // ── FlowInterfaceGenerator e2e ──────────────────────────────────────────

    @Test
    void interface_generator_writes_5_artifacts_to_disk(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        def yaml = """
            name: my-flow
            prerequisites: [x]
            nodes:
              - id: analyze
                type: llm
                action: analyze
                result: { type: String }
        """.stripIndent()

        java.nio.file.Path yamlFile = tempDir.resolve("pipeline-flow.yaml")
        java.nio.file.Files.writeString(yamlFile, yaml)
        java.nio.file.Path outDir = tempDir.resolve("gen")

        FlowInterfaceGenerator.generate(yamlFile.toFile(), outDir.toFile(), 'com.example.gen')

        java.nio.file.Path pkgDir = outDir.resolve("com/example/gen")
        assertThat(pkgDir.resolve("MyFlowPrerequisites.java")).exists()
        assertThat(pkgDir.resolve("MyFlowResult.java")).exists()
        assertThat(pkgDir.resolve("MyFlowFieldExtractor.java")).exists()
        assertThat(pkgDir.resolve("MyFlowLlmMiddleware.java")).exists()
        assertThat(pkgDir.resolve("MyFlowBase.java")).exists()
    }

    @Test
    void interface_generator_runs_validation_on_input(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        // 사이클 있는 yaml — validateAll에서 strict 실패
        def yaml = """
            nodes:
              - id: a
                type: llm
                inputs: { x: b }
              - id: b
                type: llm
                inputs: { x: a }
        """.stripIndent()

        java.nio.file.Path yamlFile = tempDir.resolve("pipeline-flow.yaml")
        java.nio.file.Files.writeString(yamlFile, yaml)
        java.nio.file.Path outDir = tempDir.resolve("gen")

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException) {
            FlowInterfaceGenerator.generate(yamlFile.toFile(), outDir.toFile(), 'p')
        }
    }
}
