package com.returney.flow.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.returney.flow.core.FlowDefinition;
import com.returney.flow.core.NodeType;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class FlowYamlParserTest {

  private final FlowYamlParser parser = new FlowYamlParser();

  @Test
  void 정상_YAML_파싱() {
    InputStream is = getClass().getClassLoader().getResourceAsStream("test-flow.yaml");
    FlowDefinition def = parser.parse(is);

    assertThat(def.name()).isEqualTo("test-pipeline");
    assertThat(def.version()).isEqualTo(1);
    assertThat(def.nodes()).hasSize(4);
    assertThat(def.edges()).hasSize(3);
    assertThat(def.parallelGroups()).hasSize(1);
    assertThat(def.parallelGroups().get(0)).containsExactly("step_a", "step_b");
  }

  @Test
  void 노드_타입_파싱() {
    InputStream is = getClass().getClassLoader().getResourceAsStream("test-flow.yaml");
    FlowDefinition def = parser.parse(is);

    assertThat(def.findNode("step_a").orElseThrow().type()).isEqualTo(NodeType.LLM);
    assertThat(def.findNode("step_c").orElseThrow().type()).isEqualTo(NodeType.TRANSFORM);
  }

  @Test
  void upstream_downstream_조회() {
    InputStream is = getClass().getClassLoader().getResourceAsStream("test-flow.yaml");
    FlowDefinition def = parser.parse(is);

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
          - { id: a, action: a, type: llm }
          - { id: b, action: b, type: llm }
        edges:
          - { from: a, to: b }
          - { from: b, to: a }
        """;

    assertThatThrownBy(() -> parser.parse(cycleYaml))
        .isInstanceOf(FlowParseException.class)
        .hasMessageContaining("cycle");
  }

  @Test
  void 존재하지_않는_노드_참조_감지() {
    String badEdgeYaml =
        """
        name: bad-edge
        version: 1
        nodes:
          - { id: a, action: a, type: llm }
        edges:
          - { from: a, to: unknown }
        """;

    assertThatThrownBy(() -> parser.parse(badEdgeYaml))
        .isInstanceOf(FlowParseException.class)
        .hasMessageContaining("unknown");
  }

  @Test
  void 빈_YAML_예외() {
    assertThatThrownBy(() -> parser.parse(""))
        .isInstanceOf(FlowParseException.class);
  }
}
