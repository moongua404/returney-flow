package com.returney.flow.domain.definition;

import java.util.Map;
import java.util.Objects;

/**
 * 파이프라인 DAG의 노드.
 *
 * @param id 노드 고유 식별자 (예: "profile_extraction")
 * @param action 프롬프트 YAML의 action 이름 (LLM/TEMPLATE 노드용; 서버 커스텀 노드는 id와 동일)
 * @param type 노드 유형
 * @param inputs 입력 변수 매핑 (변수명 → 소스 스펙)
 * @param resultType 출력 Java 타입 FQCN (모든 노드에 필수; String·Integer 등 shorthand 허용)
 * @param critical true이면 이 노드 실패 시 파이프라인 전체 실패로 처리
 */
public record PipelineNode(
    String id,
    String action,
    NodeType type,
    Map<String, String> inputs,
    String resultType,
    boolean critical) {

  public PipelineNode {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(resultType, "resultType must not be null");
    inputs = inputs != null ? Map.copyOf(inputs) : Map.of();
  }
}
