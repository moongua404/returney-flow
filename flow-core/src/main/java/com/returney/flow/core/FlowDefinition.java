package com.returney.flow.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 플로우 파이프라인 정의.
 *
 * @param name 플로우 이름
 * @param version 플로우 버전
 * @param nodes 노드 목록
 * @param edges 엣지 목록 (데이터 의존성)
 * @param parallelGroups 병렬 실행 그룹
 * @param inputs 노드별 입력 변수 매핑 (변수명 → 소스). 없으면 upstream 노드 ID로 자동 매핑.
 */
public record FlowDefinition(
    String name,
    int version,
    List<FlowNode> nodes,
    List<FlowEdge> edges,
    List<List<String>> parallelGroups,
    Map<String, Map<String, String>> inputs) {

  public FlowDefinition {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(nodes, "nodes must not be null");
    Objects.requireNonNull(edges, "edges must not be null");
    if (parallelGroups == null) {
      parallelGroups = List.of();
    }
    if (inputs == null) {
      inputs = Map.of();
    }
    nodes = List.copyOf(nodes);
    edges = List.copyOf(edges);
    parallelGroups = parallelGroups.stream().map(List::copyOf).collect(Collectors.toUnmodifiableList());
    inputs = Map.copyOf(inputs);
  }

  /** 하위 호환: inputs 없는 생성자. */
  public FlowDefinition(
      String name, int version, List<FlowNode> nodes,
      List<FlowEdge> edges, List<List<String>> parallelGroups) {
    this(name, version, nodes, edges, parallelGroups, Map.of());
  }

  /** 노드의 입력 변수 매핑을 반환한다. 정의가 없으면 빈 맵. */
  public Map<String, String> inputsOf(String nodeId) {
    return inputs.getOrDefault(nodeId, Map.of());
  }

  /** ID로 노드를 조회한다. */
  public Optional<FlowNode> findNode(String id) {
    return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
  }

  /** 특정 노드의 upstream (의존) 노드 ID 목록을 반환한다. */
  public Set<String> upstreamOf(String nodeId) {
    return edges.stream()
        .filter(e -> e.to().equals(nodeId))
        .map(FlowEdge::from)
        .collect(Collectors.toUnmodifiableSet());
  }

  /** 특정 노드의 downstream 노드 ID 목록을 반환한다. */
  public Set<String> downstreamOf(String nodeId) {
    return edges.stream()
        .filter(e -> e.from().equals(nodeId))
        .map(FlowEdge::to)
        .collect(Collectors.toUnmodifiableSet());
  }

  /** 모든 노드 ID 집합을 반환한다. */
  public Set<String> nodeIds() {
    return nodes.stream().map(FlowNode::id).collect(Collectors.toUnmodifiableSet());
  }
}
