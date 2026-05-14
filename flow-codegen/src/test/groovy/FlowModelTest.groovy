import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatThrownBy

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class FlowModelTest {

    private static Map parseYaml(String s) {
        new Yaml().load(s) as Map
    }

    @Test
    void flowName_is_camelCase_from_hyphenated_name() {
        def pipeline = parseYaml("name: my-pipeline\nnodes: []")
        def model = FlowModel.from(pipeline, 'com.example')
        assertThat(model.flowName).isEqualTo("MyPipeline")
    }

    @Test
    void prerequisites_are_extracted() {
        def pipeline = parseYaml("""
            name: test
            prerequisites:
              - name: sessionId
                type: java.lang.String
              - name: userId
                type: java.lang.String
              - name: reportText
                type: java.lang.String
            nodes: []
        """)
        def model = FlowModel.from(pipeline, 'p')
        assertThat(model.prerequisites.collect { it.name }).containsExactly("sessionId", "userId", "reportText")
        assertThat(model.prerequisites.collect { it.fqcn }).containsOnly("java.lang.String")
    }

    @Test
    void llm_actions_are_collected_for_llm_and_template_nodes_only() {
        def pipeline = parseYaml("""
            name: test
            nodes:
              - id: a
                type: llm
                action: analyze
              - id: b
                type: template
                action: render
              - id: c
                type: scatter
              - id: d
                type: gather
              - id: e
                type: transform
        """)
        def model = FlowModel.from(pipeline, 'p')
        assertThat(model.llmActions).containsExactlyInAnyOrder("analyze", "render")
    }

    @Test
    void server_nodes_are_classified_by_type() {
        def pipeline = parseYaml("""
            name: test
            nodes:
              - id: split
                type: scatter
              - id: merge
                type: gather
              - id: trans
                type: transform
              - id: skip
                type: llm
        """)
        def model = FlowModel.from(pipeline, 'p')

        assertThat(model.scatterNodes).hasSize(1)
        assertThat(model.scatterNodes[0].id).isEqualTo("split")
        assertThat(model.gatherNodes).hasSize(1)
        assertThat(model.gatherNodes[0].id).isEqualTo("merge")
        assertThat(model.transformNodes).hasSize(1)
        assertThat(model.transformNodes[0].id).isEqualTo("trans")
    }

    @Test
    void server_node_methodName_uses_camelCase_with_typeSuffix() {
        def pipeline = parseYaml("""
            name: test
            nodes:
              - id: chunk_splitter
                type: scatter
        """)
        def model = FlowModel.from(pipeline, 'p')
        assertThat(model.scatterNodes[0].methodName).isEqualTo("chunkSplitterScatter")
    }

    @Test
    void result_nodes_capture_fqcn_and_javaType() {
        def pipeline = parseYaml("""
            name: test
            nodes:
              - id: analyze
                type: llm
                result:
                  type: com.example.AnalysisResult
              - id: noResult
                type: llm
        """)
        def model = FlowModel.from(pipeline, 'p')

        assertThat(model.resultNodes).hasSize(1)
        assertThat(model.resultNodes[0].id).isEqualTo("analyze")
        assertThat(model.resultNodes[0].fqcn).isEqualTo("com.example.AnalysisResult")
        assertThat(model.resultNodes[0].javaType).isEqualTo("AnalysisResult")
        assertThat(model.resultNodes[0].importLine).contains("import com.example.AnalysisResult")
        assertThat(model.resultNodes[0].builtin).isFalse()
    }

    @Test
    void builtin_String_result_does_not_emit_import() {
        def pipeline = parseYaml("""
            name: test
            nodes:
              - id: analyze
                type: llm
                result:
                  type: String
        """)
        def model = FlowModel.from(pipeline, 'p')
        def n = model.resultNodes[0]
        assertThat(n.builtin).isTrue()
        assertThat(n.javaType).isEqualTo("String")
        assertThat(n.importLine).isNull()
    }

    @Test
    void critical_and_hook_flags_propagate() {
        def pipeline = parseYaml("""
            name: test
            nodes:
              - id: c1
                type: llm
                critical: true
                hook: false
                result: { type: String }
              - id: c2
                type: llm
                critical: false
                hook: true
                result: { type: String }
        """)
        def model = FlowModel.from(pipeline, 'p')
        assertThat(model.resultNodes.find { it.id == "c1" }.critical).isTrue()
        assertThat(model.resultNodes.find { it.id == "c2" }.hook).isTrue()
    }

    @Test
    void field_refs_built_from_dot_access_inputs() {
        def pipeline = parseYaml("""
            name: test
            nodes:
              - id: analyze
                type: llm
                result: { type: com.example.AnalysisResult }
              - id: consumer
                type: llm
                inputs:
                  text: analyze.someField
                  other: analyze.otherField
        """)
        def model = FlowModel.from(pipeline, 'p')

        assertThat(model.fieldRefs).hasSize(2)
        assertThat(model.fieldRefs.collect { it.fieldName }).containsExactlyInAnyOrder("someField", "otherField")
        // accessor는 camelCase: someField → someField (이미 camelCase)
        assertThat(model.fieldRefs[0].accessorName).matches(/[a-z][a-zA-Z]+/)
    }

    @Test
    void field_refs_skip_prerequisite_and_plain_node_refs() {
        def pipeline = parseYaml("""
            name: test
            prerequisites:
              - name: reportText
                type: java.lang.String
            nodes:
              - id: a
                type: llm
                result: { type: com.example.X }
              - id: b
                type: llm
                inputs:
                  fromPrereq: Prerequisites.reportText
                  fromNode: a
                  fromField: a.specific
        """)
        def model = FlowModel.from(pipeline, 'p')

        // Prerequisites.x와 plain node ref(a)는 fieldRefs에 안 들어감, a.specific만 들어감
        assertThat(model.fieldRefs).hasSize(1)
        assertThat(model.fieldRefs[0].nodeId).isEqualTo("a")
        assertThat(model.fieldRefs[0].fieldName).isEqualTo("specific")
    }

    @Test
    void dot_access_on_builtin_throws() {
        def pipeline = parseYaml("""
            name: test
            nodes:
              - id: a
                type: llm
                result: { type: String }
              - id: b
                type: llm
                inputs:
                  text: a.somefield
        """)

        assertThatThrownBy({ FlowModel.from(pipeline, 'p') })
            .isInstanceOf(IllegalArgumentException)
            .hasMessageContaining("Cannot use dot-access on builtin type")
    }

    // ── type helpers ────────────────────────────────────────────────────────

    @Test
    void resolveType_maps_short_names_to_fqcn() {
        assertThat(FlowModel.resolveType("String")).isEqualTo("java.lang.String")
        assertThat(FlowModel.resolveType("Integer")).isEqualTo("java.lang.Integer")
        assertThat(FlowModel.resolveType("Boolean")).isEqualTo("java.lang.Boolean")
        assertThat(FlowModel.resolveType("com.foo.Bar")).isEqualTo("com.foo.Bar")
    }

    @Test
    void isBuiltinType_classifies_correctly() {
        assertThat(FlowModel.isBuiltinType("java.lang.String")).isTrue()
        assertThat(FlowModel.isBuiltinType("java.util.List")).isTrue()
        assertThat(FlowModel.isBuiltinType("com.example.Foo")).isFalse()
    }

    @Test
    void simpleType_returns_class_part() {
        assertThat(FlowModel.simpleType("com.foo.Bar")).isEqualTo("Bar")
        assertThat(FlowModel.simpleType("com.foo.Outer\$Inner")).isEqualTo("Outer.Inner")
    }

    @Test
    void camelField_converts_snake_case() {
        assertThat(FlowModel.camelField("hello_world")).isEqualTo("helloWorld")
        assertThat(FlowModel.camelField("a_b_c")).isEqualTo("aBC")
        assertThat(FlowModel.camelField("simple")).isEqualTo("simple")
    }

    @Test
    void toCamelCase_converts_hyphenated() {
        assertThat(FlowModel.toCamelCase("my-pipeline")).isEqualTo("MyPipeline")
        assertThat(FlowModel.toCamelCase("a-b-c")).isEqualTo("ABC")
    }
}
