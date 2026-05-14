import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Assertions.assertThatCode
import static org.assertj.core.api.Assertions.assertThatThrownBy

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml

import java.nio.file.Files
import java.nio.file.Path

class FlowValidatorTest {

    private static Map parseYaml(String s) {
        new Yaml().load(s) as Map
    }

    // ── strict 룰 ───────────────────────────────────────────────────────────

    @Test
    void duplicate_node_id_strict_fail() {
        def pipeline = parseYaml("""
            nodes:
              - id: a
                type: llm
              - id: a
                type: llm
        """)

        assertThatThrownBy({ FlowValidator.validateAll(pipeline, null) })
            .isInstanceOf(IllegalArgumentException)
            .hasMessageContaining("Duplicate node id: a")
    }

    @Test
    void node_missing_id_strict_fail() {
        def pipeline = parseYaml("""
            nodes:
              - type: llm
        """)

        assertThatThrownBy({ FlowValidator.validateAll(pipeline, null) })
            .isInstanceOf(IllegalArgumentException)
            .hasMessageContaining("missing 'id'")
    }

    @Test
    void unknown_input_source_strict_fail() {
        def pipeline = parseYaml("""
            nodes:
              - id: b
                type: llm
                inputs:
                  text: nonexistent_node
        """)

        assertThatThrownBy({ FlowValidator.validateAll(pipeline, null) })
            .isInstanceOf(IllegalArgumentException)
            .hasMessageContaining("unknown upstream node")
            .hasMessageContaining("nonexistent_node")
    }

    @Test
    void empty_input_source_strict_fail() {
        def pipeline = parseYaml("""
            nodes:
              - id: a
                type: llm
                inputs:
                  text: ""
        """)

        assertThatThrownBy({ FlowValidator.validateAll(pipeline, null) })
            .isInstanceOf(IllegalArgumentException)
            .hasMessageContaining("empty source")
    }

    @Test
    void cycle_detection_strict_fail() {
        // a → b → c → a (자기 자신을 향한 사이클)
        def pipeline = parseYaml("""
            nodes:
              - id: a
                type: llm
                inputs: { x: c }
              - id: b
                type: llm
                inputs: { x: a }
              - id: c
                type: llm
                inputs: { x: b }
        """)

        assertThatThrownBy({ FlowValidator.validateAll(pipeline, null) })
            .isInstanceOf(IllegalArgumentException)
            .hasMessageContaining("Cycle detected")
    }

    @Test
    void prerequisite_ref_undeclared_strict_fail() {
        def pipeline = parseYaml("""
            prerequisites:
              - name: sessionId
                type: java.lang.String
            nodes:
              - id: a
                type: llm
                inputs:
                  text: Prerequisites.unknownField
        """)

        assertThatThrownBy({ FlowValidator.validateAll(pipeline, null) })
            .isInstanceOf(IllegalArgumentException)
            .hasMessageContaining("undeclared prerequisite: unknownField")
    }

    @Test
    void valid_dag_passes() {
        def pipeline = parseYaml("""
            prerequisites:
              - name: sessionId
                type: java.lang.String
              - name: reportText
                type: java.lang.String
            nodes:
              - id: a
                type: llm
                inputs: { text: Prerequisites.reportText }
              - id: b
                type: llm
                inputs: { upstream: a }
              - id: c
                type: llm
                inputs: { upstream: a }
              - id: d
                type: llm
                inputs:
                  fromB: b
                  fromC: c
        """)

        assertThatCode({ FlowValidator.validateAll(pipeline, null) })
            .doesNotThrowAnyException()
    }

    @Test
    void inputs_section_missing_is_ok() {
        // inputs 없는 노드 (예: scatter without inputs)
        def pipeline = parseYaml("""
            nodes:
              - id: a
                type: scatter
        """)

        assertThatCode({ FlowValidator.validateAll(pipeline, null) })
            .doesNotThrowAnyException()
    }

    // ── lenient 룰 (prompts 검증) ────────────────────────────────────────────

    @Test
    void missing_prompt_yaml_is_lenient_warning(@TempDir Path tempDir) {
        Path promptsDir = tempDir.resolve("prompts")
        Files.createDirectories(promptsDir)
        // prompts/*.yaml 없음 — 'analyze' 액션을 정의하는 파일 없음

        def pipeline = parseYaml("""
            nodes:
              - id: analyze
                type: llm
                action: analyze
        """)

        // lenient — 빌드는 통과하고 stderr 경고만
        assertThatCode({ FlowValidator.validateAll(pipeline, promptsDir.toFile()) })
            .doesNotThrowAnyException()
    }

    @Test
    void prompt_action_match_works(@TempDir Path tempDir) {
        Path promptsDir = tempDir.resolve("prompts")
        Files.createDirectories(promptsDir)
        Files.writeString(
            promptsDir.resolve("analyze.yaml"),
            "action: analyze\npromptTemplate: 'hi {{x}}'\n"
        )

        def pipeline = parseYaml("""
            nodes:
              - id: analyze
                type: llm
                action: analyze
                inputs: { x: Prerequisites.x }
            prerequisites:
              - name: x
                type: java.lang.String
        """)

        assertThatCode({ FlowValidator.validateAll(pipeline, promptsDir.toFile()) })
            .doesNotThrowAnyException()
    }

    @Test
    void prompt_var_missing_in_inputs_is_lenient_warning(@TempDir Path tempDir) {
        Path promptsDir = tempDir.resolve("prompts")
        Files.createDirectories(promptsDir)
        // prompt이 {{missingVar}}를 참조하지만 노드 inputs에 없음
        Files.writeString(
            promptsDir.resolve("analyze.yaml"),
            "action: analyze\npromptTemplate: 'hi {{missingVar}}'\n"
        )

        def pipeline = parseYaml("""
            nodes:
              - id: analyze
                type: llm
                action: analyze
                inputs: { other: Prerequisites.other }
            prerequisites:
              - name: other
                type: java.lang.String
        """)

        // lenient — stderr 경고지만 빌드는 통과
        assertThatCode({ FlowValidator.validateAll(pipeline, promptsDir.toFile()) })
            .doesNotThrowAnyException()
    }

    @Test
    void config_ref_with_colon_is_not_validated(@TempDir Path tempDir) {
        Path promptsDir = tempDir.resolve("prompts")
        Files.createDirectories(promptsDir)
        // {{methodology:full_prompt}} 형태는 yaml 내부 분기 참조, inputs에 없어도 OK
        Files.writeString(
            promptsDir.resolve("analyze.yaml"),
            """
            action: analyze
            promptTemplate: |
              {{methodology:full_prompt}}
              {{x}}
            methodology:
              STAR:
                full_prompt: STAR guide
            """.stripIndent()
        )

        def pipeline = parseYaml("""
            nodes:
              - id: analyze
                type: llm
                action: analyze
                inputs: { x: Prerequisites.x }
            prerequisites:
              - name: x
                type: java.lang.String
        """)

        assertThatCode({ FlowValidator.validateAll(pipeline, promptsDir.toFile()) })
            .doesNotThrowAnyException()
    }

    @Test
    void strict_prompts_mode_throws_on_missing(@TempDir Path tempDir) {
        Path promptsDir = tempDir.resolve("prompts")
        Files.createDirectories(promptsDir)

        def pipeline = parseYaml("""
            nodes:
              - id: analyze
                type: llm
                action: analyze
        """)

        assertThatThrownBy({ FlowValidator.validateAll(pipeline, promptsDir.toFile(), true) })
            .isInstanceOf(IllegalArgumentException)
            .hasMessageContaining("prompts 정합성 오류")
    }

    @Test
    void server_node_types_are_skipped_in_action_mapping(@TempDir Path tempDir) {
        Path promptsDir = tempDir.resolve("prompts")
        Files.createDirectories(promptsDir)
        // scatter/gather/transform은 prompt이 없어도 됨

        def pipeline = parseYaml("""
            nodes:
              - id: split
                type: scatter
              - id: merge
                type: gather
                inputs: { x: split }
              - id: trans
                type: transform
                inputs: { x: split }
        """)

        assertThatCode({ FlowValidator.validateAll(pipeline, promptsDir.toFile(), true) })
            .doesNotThrowAnyException()
    }
}
