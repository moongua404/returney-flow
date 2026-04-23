package com.returney.flow.adapter.parser;

import com.returney.flow.domain.definition.NodeType;
import com.returney.flow.domain.definition.PipelineDefinition;
import com.returney.flow.domain.definition.PipelineEdge;
import com.returney.flow.domain.definition.PipelineNode;
import com.returney.flow.domain.definition.PipelineParseException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

/** pipeline-flow.yaml 파싱 및 검증. */
public class PipelineYamlParser {

  private static final String PREREQ_PREFIX = "Prerequisites.";

  static class PipelineConfig {
    public String name;
    public int version;
    public List<String> prerequisites;
    public List<NodeConfig> nodes;
  }

  static class NodeConfig {
    public String id;
    public String action;
    public String type;
    public boolean critical;
    public ResultConfig result;
    public Map<String, String> inputs;
  }

  static class ResultConfig {
    public String type;
  }

  public PipelineDefinition parse(String yamlContent) {
    PipelineConfig config = new Yaml(new Constructor(PipelineConfig.class, new LoaderOptions())).load(yamlContent);
    return parseRoot(config);
  }

  public PipelineDefinition parse(InputStream inputStream) {
    PipelineConfig config = new Yaml(new Constructor(PipelineConfig.class, new LoaderOptions())).load(inputStream);
    return parseRoot(config);
  }

  private PipelineDefinition parseRoot(PipelineConfig config) {
    if (config == null) throw new PipelineParseException("YAML content is empty");
    if (config.name == null) throw new PipelineParseException("Missing required field: name");

    List<String> prerequisites = config.prerequisites != null ? config.prerequisites : List.of();
    List<NodeConfig> rawNodes = config.nodes != null ? config.nodes : List.of();

    List<PipelineNode> nodes = rawNodes.stream().map(this::parseNode).collect(Collectors.toList());

    List<PipelineEdge> edges = deriveEdges(nodes);

    PipelineDefinition definition = new PipelineDefinition(config.name, config.version, nodes, edges, prerequisites);
    validate(definition);
    return definition;
  }

  private PipelineNode parseNode(NodeConfig raw) {
    if (raw.id == null) throw new PipelineParseException("Missing required field: id");
    if (raw.type == null) throw new PipelineParseException("Missing required field: type for node: " + raw.id);

    NodeType type;
    try {
      type = NodeType.valueOf(raw.type.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new PipelineParseException("Unknown node type: " + raw.type + " for node: " + raw.id);
    }

    String action = raw.action != null ? raw.action : raw.id;
    Map<String, String> inputs = raw.inputs != null ? raw.inputs : Map.of();
    String resultType = parseResultType(raw.result, raw.id);

    return new PipelineNode(raw.id, action, type, inputs, resultType, raw.critical);
  }

  private String parseResultType(ResultConfig result, String nodeId) {
    if (result != null && result.type != null) {
      return resolveTypeName(result.type);
    }
    throw new PipelineParseException("Missing required result.type for node: " + nodeId);
  }

  /** String, Integer 등 shorthand를 FQCN으로 확장한다. */
  private String resolveTypeName(String typeName) {
    return switch (typeName) {
      case "String" -> "java.lang.String";
      case "Integer" -> "java.lang.Integer";
      case "Long" -> "java.lang.Long";
      case "Boolean" -> "java.lang.Boolean";
      case "Double" -> "java.lang.Double";
      default -> typeName;
    };
  }

  /**
   * 각 노드의 inputs 선언에서 엣지를 자동으로 유도한다.
   *
   * <ul>
   *   <li>{@code "Prerequisites.xxx"} → 엣지 없음
   *   <li>{@code "nodeId"} 또는 {@code "nodeId.fieldName"} → edge(nodeId → currentNode)
   * </ul>
   */
  private List<PipelineEdge> deriveEdges(List<PipelineNode> nodes) {
    Set<PipelineEdge> edgeSet = new HashSet<>();
    for (PipelineNode node : nodes) {
      for (String sourceSpec : node.inputs().values()) {
        if (sourceSpec.startsWith(PREREQ_PREFIX)) continue;
        String sourceNodeId = sourceSpec.contains(".")
            ? sourceSpec.substring(0, sourceSpec.indexOf('.'))
            : sourceSpec;
        edgeSet.add(new PipelineEdge(sourceNodeId, node.id()));
      }
    }
    return new ArrayList<>(edgeSet);
  }

  private void validate(PipelineDefinition def) {
    validateNodeRefs(def);
    detectCycle(def);
    validatePrerequisiteRefs(def);
    validateGatherUpstreams(def);
  }

  private void validateNodeRefs(PipelineDefinition def) {
    Set<String> nodeIds = new HashSet<>(def.nodeIds());
    for (PipelineNode node : def.nodes()) {
      for (String sourceSpec : node.inputs().values()) {
        if (sourceSpec.startsWith(PREREQ_PREFIX)) continue;
        String sourceNodeId = sourceSpec.contains(".")
            ? sourceSpec.substring(0, sourceSpec.indexOf('.'))
            : sourceSpec;
        if (!nodeIds.contains(sourceNodeId)) {
          throw new PipelineParseException(
              "Node '" + node.id() + "' inputs reference unknown node: " + sourceNodeId);
        }
      }
    }
  }

  private void validatePrerequisiteRefs(PipelineDefinition def) {
    Set<String> declared = new HashSet<>(def.prerequisites());
    for (PipelineNode node : def.nodes()) {
      for (String sourceSpec : node.inputs().values()) {
        if (sourceSpec.startsWith(PREREQ_PREFIX)) {
          String name = sourceSpec.substring(PREREQ_PREFIX.length());
          if (!declared.contains(name)) {
            throw new PipelineParseException(
                "Node '" + node.id() + "' references undeclared prerequisite: " + name);
          }
        }
      }
    }
  }

  /**
   * GATHER 노드는 SCATTER이거나 SCATTER 업스트림을 가진 LLM(fan-out) 업스트림이 있어야 한다.
   */
  private void validateGatherUpstreams(PipelineDefinition def) {
    Map<String, NodeType> typeMap = def.nodes().stream()
        .collect(Collectors.toMap(PipelineNode::id, PipelineNode::type));

    for (PipelineNode node : def.nodes()) {
      if (node.type() != NodeType.GATHER) continue;
      boolean found = false;
      outer:
      for (String upstreamId : def.upstreamOf(node.id())) {
        NodeType upType = typeMap.get(upstreamId);
        if (upType == NodeType.SCATTER) { found = true; break; }
        if (upType == NodeType.LLM) {
          for (String upUpstreamId : def.upstreamOf(upstreamId)) {
            if (typeMap.get(upUpstreamId) == NodeType.SCATTER) { found = true; break outer; }
          }
        }
      }
      if (!found) {
        throw new PipelineParseException(
            "GATHER node '" + node.id() + "' has no scatter-producing upstream");
      }
    }
  }

  private void detectCycle(PipelineDefinition def) {
    Map<String, Long> inDegree =
        def.nodeIds().stream().collect(Collectors.toMap(id -> id, id -> 0L));
    for (PipelineEdge edge : def.edges()) {
      inDegree.merge(edge.to(), 1L, Long::sum);
    }

    Deque<String> queue = new ArrayDeque<>();
    for (Map.Entry<String, Long> entry : inDegree.entrySet()) {
      if (entry.getValue() == 0) queue.add(entry.getKey());
    }

    int visited = 0;
    while (!queue.isEmpty()) {
      String nodeId = queue.poll();
      visited++;
      for (String downstream : def.downstreamOf(nodeId)) {
        long newDegree = inDegree.get(downstream) - 1;
        inDegree.put(downstream, newDegree);
        if (newDegree == 0) queue.add(downstream);
      }
    }

    if (visited != def.nodes().size()) {
      throw new PipelineParseException("Pipeline contains a cycle");
    }
  }
}
