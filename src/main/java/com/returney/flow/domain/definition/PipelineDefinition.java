package com.returney.flow.domain.definition;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 파이프라인 정의.
 *
 * @param name 파이프라인 이름
 * @param version 버전
 * @param nodes 노드 목록
 * @param edges 엣지 목록 (inputs 선언에서 자동 유도됨)
 * @param prerequisites 파이프라인 외부에서 주입되는 전제 변수 이름 목록
 */
public record PipelineDefinition(
    String name,
    int version,
    List<PipelineNode> nodes,
    List<PipelineEdge> edges,
    List<String> prerequisites) {

  public PipelineDefinition {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(nodes, "nodes must not be null");
    Objects.requireNonNull(edges, "edges must not be null");
    if (prerequisites == null) {
      prerequisites = List.of();
    }
    nodes = List.copyOf(nodes);
    edges = List.copyOf(edges);
    prerequisites = List.copyOf(prerequisites);
  }

  /** 노드의 입력 변수 매핑을 반환한다. 정의가 없으면 빈 맵. */
  public Map<String, String> inputsOf(String nodeId) {
    return findNode(nodeId).map(PipelineNode::inputs).orElse(Map.of());
  }

  /** ID로 노드를 조회한다. */
  public Optional<PipelineNode> findNode(String id) {
    return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
  }

  /** 특정 노드의 upstream (의존) 노드 ID 목록을 반환한다. */
  public Set<String> upstreamOf(String nodeId) {
    return edges.stream()
        .filter(e -> e.to().equals(nodeId))
        .map(PipelineEdge::from)
        .collect(Collectors.toUnmodifiableSet());
  }

  /** 특정 노드의 downstream 노드 ID 목록을 반환한다. */
  public Set<String> downstreamOf(String nodeId) {
    return edges.stream()
        .filter(e -> e.from().equals(nodeId))
        .map(PipelineEdge::to)
        .collect(Collectors.toUnmodifiableSet());
  }

  /** 모든 노드 ID 집합을 반환한다. */
  public Set<String> nodeIds() {
    return nodes.stream().map(PipelineNode::id).collect(Collectors.toUnmodifiableSet());
  }
}
