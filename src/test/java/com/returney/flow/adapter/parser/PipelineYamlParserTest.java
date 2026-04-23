package com.returney.flow.adapter.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.returney.flow.domain.definition.NodeType;
import com.returney.flow.domain.definition.PipelineDefinition;
import com.returney.flow.domain.definition.PipelineParseException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class PipelineYamlParserTest {

  private final PipelineYamlParser parser = new PipelineYamlParser();

  @Test
  void 정상_YAML_파싱() {
    InputStream is = getClass().getClassLoader().getResourceAsStream("test-flow.yaml");
    PipelineDefinition def = parser.parse(is);

    assertThat(def.name()).isEqualTo("test-pipeline");
    assertThat(def.version()).isEqualTo(1);
    assertThat(def.nodes()).hasSize(4);
    assertThat(def.edges()).hasSize(3);
  }

  @Test
  void 노드_타입_파싱() {
    InputStream is = getClass().getClassLoader().getResourceAsStream("test-flow.yaml");
    PipelineDefinition def = parser.parse(is);

    assertThat(def.findNode("step_a").orElseThrow().type()).isEqualTo(NodeType.LLM);
    assertThat(def.findNode("step_c").orElseThrow().type()).isEqualTo(NodeType.TRANSFORM);
  }

  @Test
  void result_type_파싱() {
    InputStream is = getClass().getClassLoader().getResourceAsStream("test-flow.yaml");
    PipelineDefinition def = parser.parse(is);

    assertThat(def.findNode("step_a").orElseThrow().resultType()).isEqualTo("java.lang.String");
  }

  @Test
  void inputs_edge_자동_유도() {
    InputStream is = getClass().getClassLoader().getResourceAsStream("test-flow.yaml");
    PipelineDefinition def = parser.parse(is);

    assertThat(def.upstreamOf("step_c")).containsExactlyInAnyOrder("step_a", "step_b");
    assertThat(def.downstreamOf("step_c")).containsExactly("step_d");
    assertThat(def.upstreamOf("step_a")).isEmpty();
    assertThat(def.downstreamOf("step_d")).isEmpty();
  }

  @Test
  void 순환_참조_감지() {
    String cycleYaml =
        """
        name: cycle-test
        version: 1
        nodes:
          - id: a
            action: a
            type: llm
            result:
              type: String
            inputs:
              x: "b"
          - id: b
            action: b
            type: llm
            result:
              type: String
            inputs:
              x: "a"
        """;

    assertThatThrownBy(() -> parser.parse(cycleYaml))
        .isInstanceOf(PipelineParseException.class)
        .hasMessageContaining("cycle");
  }

  @Test
  void 존재하지_않는_노드_참조_감지() {
    String badInputYaml =
        """
        name: bad-input
        version: 1
        nodes:
          - id: a
            action: a
            type: llm
            result:
              type: String
            inputs:
              x: "unknown"
        """;

    assertThatThrownBy(() -> parser.parse(badInputYaml))
        .isInstanceOf(PipelineParseException.class)
        .hasMessageContaining("unknown");
  }

  @Test
  void result_type_누락_시_예외() {
    String noResultYaml =
        """
        name: no-result
        version: 1
        nodes:
          - id: a
            action: a
            type: llm
        """;

    assertThatThrownBy(() -> parser.parse(noResultYaml))
        .isInstanceOf(PipelineParseException.class)
        .hasMessageContaining("result.type");
  }

  @Test
  void 빈_YAML_예외() {
    assertThatThrownBy(() -> parser.parse(""))
        .isInstanceOf(PipelineParseException.class);
  }

  @Test
  void Prerequisites_미선언_참조_감지() {
    String yaml =
        """
        name: prereq-test
        version: 1
        prerequisites:
          - knownKey
        nodes:
          - id: a
            action: a
            type: llm
            result:
              type: String
            inputs:
              x: "Prerequisites.unknownKey"
        """;

    assertThatThrownBy(() -> parser.parse(yaml))
        .isInstanceOf(PipelineParseException.class)
        .hasMessageContaining("unknownKey");
  }

  @Test
  void GATHER_scatter_업스트림_없음_감지() {
    String yaml =
        """
        name: gather-test
        version: 1
        nodes:
          - id: a
            action: a
            type: llm
            result:
              type: String
          - id: b
            type: gather
            result:
              type: String
            inputs:
              chunks: "a"
        """;

    assertThatThrownBy(() -> parser.parse(yaml))
        .isInstanceOf(PipelineParseException.class)
        .hasMessageContaining("b");
  }
}
