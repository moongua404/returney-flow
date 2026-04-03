package com.returney.flow.schema;

import com.returney.flow.core.FlowDefinition;
import com.returney.flow.core.FlowEdge;
import com.returney.flow.core.FlowNode;
import com.returney.flow.core.NodeType;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;

/** pipeline-flow.yaml 파싱 및 검증. */
public class FlowYamlParser {

  /**
   * YAML 문자열을 파싱하여 FlowDefinition을 생성한다.
   *
   * @throws FlowParseException 파싱 또는 검증 실패 시
   */
  public FlowDefinition parse(String yamlContent) {
    Yaml yaml = new Yaml();
    Map<String, Object> root = yaml.load(yamlContent);
    return parseRoot(root);
  }

  /** InputStream에서 YAML을 파싱한다. */
  public FlowDefinition parse(InputStream inputStream) {
    Yaml yaml = new Yaml();
    Map<String, Object> root = yaml.load(inputStream);
    return parseRoot(root);
  }

  @SuppressWarnings("unchecked")
  private FlowDefinition parseRoot(Map<String, Object> root) {
    if (root == null) {
      throw new FlowParseException("YAML content is empty");
    }

    String name = requireString(root, "name");
    int version = requireInt(root, "version");

    List<Map<String, Object>> rawNodes =
        (List<Map<String, Object>>) root.getOrDefault("nodes", List.of());
    List<Map<String, Object>> rawEdges =
        (List<Map<String, Object>>) root.getOrDefault("edges", List.of());
    List<List<String>> parallelGroups =
        (List<List<String>>) root.getOrDefault("parallelGroups", List.of());

    List<FlowNode> nodes = rawNodes.stream().map(this::parseNode).collect(Collectors.toList());
    List<FlowEdge> edges = rawEdges.stream().map(this::parseEdge).collect(Collectors.toList());

    // inputs 섹션 파싱 (선택적)
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> rawInputs =
        (Map<String, Map<String, Object>>) root.getOrDefault("inputs", Map.of());
    Map<String, Map<String, String>> inputs = new HashMap<>();
    for (Map.Entry<String, Map<String, Object>> entry : rawInputs.entrySet()) {
      Map<String, String> nodeInputs = new HashMap<>();
      for (Map.Entry<String, Object> varEntry : entry.getValue().entrySet()) {
        nodeInputs.put(varEntry.getKey(), varEntry.getValue().toString());
      }
      inputs.put(entry.getKey(), Map.copyOf(nodeInputs));
    }

    FlowDefinition definition = new FlowDefinition(name, version, nodes, edges, parallelGroups, inputs);
    validate(definition);
    return definition;
  }

  private FlowNode parseNode(Map<String, Object> raw) {
    String id = requireString(raw, "id");
    String action = requireString(raw, "action");
    String typeStr = requireString(raw, "type");
    NodeType type;
    try {
      type = NodeType.valueOf(typeStr.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new FlowParseException("Unknown node type: " + typeStr + " for node: " + id);
    }
    return new FlowNode(id, action, type);
  }

  private FlowEdge parseEdge(Map<String, Object> raw) {
    String from = requireString(raw, "from");
    String to = requireString(raw, "to");
    return new FlowEdge(from, to);
  }

  private void validate(FlowDefinition def) {
    Set<String> nodeIds = def.nodeIds();

    // 엣지의 from/to가 존재하는 노드인지 검증
    for (FlowEdge edge : def.edges()) {
      if (!nodeIds.contains(edge.from())) {
        throw new FlowParseException("Edge references unknown source node: " + edge.from());
      }
      if (!nodeIds.contains(edge.to())) {
        throw new FlowParseException("Edge references unknown target node: " + edge.to());
      }
    }

    // parallelGroups의 노드 ID가 존재하는지 검증
    for (List<String> group : def.parallelGroups()) {
      for (String id : group) {
        if (!nodeIds.contains(id)) {
          throw new FlowParseException("Parallel group references unknown node: " + id);
        }
      }
    }

    // 순환 참조 검증 (BFS 기반 토폴로지 정렬)
    detectCycle(def);
  }

  private void detectCycle(FlowDefinition def) {
    Map<String, Long> inDegree =
        def.nodeIds().stream().collect(Collectors.toMap(id -> id, id -> 0L));
    for (FlowEdge edge : def.edges()) {
      inDegree.merge(edge.to(), 1L, Long::sum);
    }

    Deque<String> queue = new ArrayDeque<>();
    for (Map.Entry<String, Long> entry : inDegree.entrySet()) {
      if (entry.getValue() == 0) {
        queue.add(entry.getKey());
      }
    }

    int visited = 0;
    while (!queue.isEmpty()) {
      String node = queue.poll();
      visited++;
      for (String downstream : def.downstreamOf(node)) {
        long newDegree = inDegree.get(downstream) - 1;
        inDegree.put(downstream, newDegree);
        if (newDegree == 0) {
          queue.add(downstream);
        }
      }
    }

    if (visited != def.nodes().size()) {
      throw new FlowParseException("Flow contains a cycle");
    }
  }

  private String requireString(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      throw new FlowParseException("Missing required field: " + key);
    }
    return value.toString();
  }

  private int requireInt(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      throw new FlowParseException("Missing required field: " + key);
    }
    if (value instanceof Number n) {
      return n.intValue();
    }
    throw new FlowParseException("Field " + key + " must be an integer");
  }
}
