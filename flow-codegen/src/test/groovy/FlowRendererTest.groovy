import static org.assertj.core.api.Assertions.assertThat

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class FlowRendererTest {

    private static FlowModel buildModel(String yamlBody) {
        def pipeline = new Yaml().load(yamlBody) as Map
        FlowModel.from(pipeline, 'com.example.generated.pipeline')
    }

    // ── pipelineBase: 단일 결과 케이스 ──────────────────────────────────────

    @Test
    void pipeline_base_single_result_returns_node_type_directly() {
        def model = buildModel("""
            name: my-flow
            prerequisites:
              - name: x
                type: java.lang.String
            nodes:
              - id: analyze
                type: llm
                action: analyze
                result: { type: String }
        """)

        def output = FlowRenderer.pipelineBase(model)

        assertThat(output).contains("public abstract class MyFlowBase {")
        // typed run — yaml prereqs가 메서드 파라미터로 펼쳐짐
        assertThat(output).contains("public final String run(")
        assertThat(output).contains("UUID sessionId,")
        assertThat(output).contains("String x")
        // 단일 결과 → wrapper 없이 바로 리턴
        assertThat(output).contains("return analyze;")
        assertThat(output).doesNotContain("new MyFlowResult(")
    }

    @Test
    void typed_run_scatters_prereqs_into_method_params() {
        def model = buildModel("""
            name: scattered
            prerequisites:
              - name: foo
                type: java.lang.String
              - name: bar
                type: java.lang.String
              - name: baz
                type: java.lang.String
            nodes:
              - id: a
                type: llm
                action: a
                result: { type: String }
        """)
        def output = FlowRenderer.pipelineBase(model)

        // 각 prereq가 String 파라미터로
        assertThat(output).contains("String foo")
        assertThat(output).contains("String bar")
        assertThat(output).contains("String baz")
        // 내부에서 Map.ofEntries로 빌드
        assertThat(output).contains("Map.ofEntries(")
        assertThat(output).contains('"foo", (Object) foo')
        assertThat(output).contains('"bar", (Object) bar')
        assertThat(output).contains('"baz", (Object) baz')
    }

    @Test
    void typed_run_no_prereqs_emits_session_only_signature() {
        def model = buildModel("""
            name: empty-prereq
            nodes:
              - id: a
                type: llm
                action: a
                result: { type: String }
        """)
        def output = FlowRenderer.pipelineBase(model)
        assertThat(output).contains("public final String run(UUID sessionId)")
    }

    @Test
    void pipeline_base_runInternal_only_no_listener() {
        // runWithOptions/listener escape hatch 통째 폐기 — Map 직접 호출은 protected runInternal로만,
        // ExecutionListener는 노출 안 함 (조건부 실행은 yaml CONDITIONAL이, 진행률은 호출자 phase 갱신으로).
        def model = buildModel("""
            name: t
            prerequisites:
              - name: x
                type: java.lang.String
            nodes:
              - id: a
                type: llm
                action: a
                result: { type: String }
        """)
        def output = FlowRenderer.pipelineBase(model)
        assertThat(output).contains("public final String runInternal(")
        // listener/nodeIds/ExecutionConfig 모두 시그니처에서 사라짐
        assertThat(output).doesNotContain("runWithOptions")
        assertThat(output).doesNotContain("Set<String> nodeIds")
        assertThat(output).doesNotContain("ExecutionConfig config,")
        assertThat(output).doesNotContain("ExecutionListener listener")
    }

    @Test
    void pipeline_base_single_result_critical_check_uses_result_null() {
        def model = buildModel("""
            name: t
            nodes:
              - id: a
                type: llm
                action: a
                critical: true
                result: { type: String }
        """)

        def output = FlowRenderer.pipelineBase(model)
        assertThat(output).contains("public final boolean isCriticalFailure(String result)")
        assertThat(output).contains("return result == null;")
    }

    // ── pipelineBase: 다중 결과 케이스 (output.type 명시) ──────────────────

    @Test
    void pipeline_base_multi_result_uses_declared_output_type_constructor() {
        def model = buildModel("""
            name: multi
            output:
              type: com.example.MultiOutcome
            nodes:
              - id: foo
                type: llm
                action: foo
                result: { type: String }
              - id: bar
                type: llm
                action: bar
                result: { type: Integer }
        """)

        def output = FlowRenderer.pipelineBase(model)

        assertThat(output).contains("public final MultiOutcome run(")
        assertThat(output).contains("return new MultiOutcome(foo, bar);")
        assertThat(output).contains("import com.example.MultiOutcome;")
    }

    @Test
    void pipeline_base_multi_result_critical_check_uses_accessors() {
        def model = buildModel("""
            name: multi
            output:
              type: com.example.MultiOutcome
            nodes:
              - id: c1
                type: llm
                action: c1
                critical: true
                result: { type: String }
              - id: c2
                type: llm
                action: c2
                critical: false
                result: { type: String }
        """)

        def output = FlowRenderer.pipelineBase(model)

        assertThat(output).contains("if (result.c1() == null) return true;")
        assertThat(output).doesNotContain("if (result.c2() == null) return true;")
    }

    @Test
    void multi_result_without_output_type_falls_back_to_void() {
        // FlowModel 단계는 lenient — output.type 미선언이면 Void로 폴백.
        // 실제 production yaml은 단일 결과 OR output.type 명시 둘 중 하나로 검증된 상태.
        def model = buildModel("""
            name: bad
            nodes:
              - id: a
                type: llm
                action: a
                result: { type: String }
              - id: b
                type: llm
                action: b
                result: { type: String }
        """)
        assertThat(model.outputJavaType).isEqualTo("Void")
    }

    // ── pipelineBase 공통 ─────────────────────────────────────────────────

    @Test
    void pipeline_base_loads_only_llm_actions_into_renderer() {
        def model = buildModel("""
            name: test
            nodes:
              - id: a
                type: llm
                action: foo
                result: { type: String }
              - id: b
                type: scatter
              - id: c
                type: template
                action: bar
        """)

        def output = FlowRenderer.pipelineBase(model)
        // foo, bar만 등록 (scatter는 제외)
        assertThat(output).contains("FLOW_FACTORY.classpathRenderer(\"foo\", \"bar\")")
    }

    @Test
    void pipeline_base_no_extension_hooks_emitted() {
        // YAGNI: 4개 hook (apiKeySupplier/rateLimiter/createLlmExecutor/createLlmMiddleware) 제거됨.
        // RateLimiter/ApiKeySupplier는 flow-core 내부 디테일이고, LlmExecutor는 생성자 주입.
        def model = buildModel("""
            name: t
            nodes:
              - id: a
                type: llm
                action: a
                result: { type: String }
        """)
        def output = FlowRenderer.pipelineBase(model)

        assertThat(output).doesNotContain("apiKeySupplier")
        assertThat(output).doesNotContain("rateLimiter")
        assertThat(output).doesNotContain("createLlmExecutor")
        assertThat(output).doesNotContain("createLlmMiddleware")
        assertThat(output).doesNotContain("cachedLlmExecutor")
    }

    @Test
    void pipeline_base_constructor_takes_executor_and_llmExecutor() {
        def model = buildModel("""
            name: t
            nodes:
              - id: a
                type: llm
                action: a
                result: { type: String }
        """)
        def output = FlowRenderer.pipelineBase(model)

        assertThat(output).contains("protected TBase(Executor executor, LlmExecutor llmExecutor)")
        assertThat(output).contains("private final LlmExecutor llmExecutor;")
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
                result: { type: String }
              - id: codebase_summary
                type: transform
        """)

        def output = FlowRenderer.pipelineBase(model)

        assertThat(output).contains("protected abstract java.util.List<String> chunkSplitterScatter")
        assertThat(output).contains("protected abstract String chunkMergerGather")
        assertThat(output).contains("protected abstract String codebaseSummaryTransform")
    }

    // ── FieldExtractor inline ─────────────────────────────────────────────

    @Test
    void field_extractor_inline_with_no_refs_throws() {
        def model = buildModel("""
            name: test
            nodes:
              - id: a
                type: llm
                action: a
                result: { type: String }
        """)

        def output = FlowRenderer.pipelineBase(model)
        // anonymous NodeOutputExtractor 안에 throw 본문
        assertThat(output).contains("private NodeOutputExtractor buildFieldExtractor()")
        assertThat(output).contains("Unknown field reference: ")
    }

    @Test
    void field_extractor_inline_with_refs_emits_switch() {
        def model = buildModel("""
            name: test
            output:
              type: com.example.Outcome
            nodes:
              - id: analyze
                type: llm
                action: analyze
                result: { type: com.example.AnalysisResult }
              - id: consumer
                type: llm
                action: consumer
                result: { type: String }
                inputs:
                  text: analyze.someField
        """)

        def output = FlowRenderer.pipelineBase(model)
        assertThat(output).contains("case \"analyze.someField\" ->")
        assertThat(output).contains("GSON.fromJson(LlmJsonExtractor.extract(output), AnalysisResult.class)")
    }

    // ── FlowInterfaceGenerator e2e ────────────────────────────────────────

    @Test
    void interface_generator_writes_only_base_to_pipeline_subpackage(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        def yaml = """
            name: my-flow
            prerequisites:
              - name: x
                type: java.lang.String
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

        // pipeline subpackage에 Runner + Input만 생성됨 (구 Base/Prerequisites/Result/FieldExtractor/LlmMiddleware 제거됨)
        java.nio.file.Path pkgDir = outDir.resolve("com/example/gen/pipeline")
        assertThat(pkgDir.resolve("MyFlowRunner.java")).exists()
        assertThat(pkgDir.resolve("MyFlowInput.java")).exists()
        assertThat(pkgDir.resolve("MyFlowBase.java")).doesNotExist()
        assertThat(pkgDir.resolve("MyFlowPrerequisites.java")).doesNotExist()
        assertThat(pkgDir.resolve("MyFlowResult.java")).doesNotExist()
        assertThat(pkgDir.resolve("MyFlowFieldExtractor.java")).doesNotExist()
        assertThat(pkgDir.resolve("MyFlowLlmMiddleware.java")).doesNotExist()
    }

    @Test
    void interface_generator_runs_validation_on_input(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
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
