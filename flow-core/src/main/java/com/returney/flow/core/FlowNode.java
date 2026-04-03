package com.returney.flow.core;

import java.util.Objects;

/**
 * 플로우 그래프의 노드.
 *
 * @param id 노드 고유 식별자 (예: "profile_generation")
 * @param action 프롬프트 YAML의 action과 매칭되는 이름
 * @param type 노드 유형 (LLM 호출 또는 데이터 변환)
 */
public record FlowNode(String id, String action, NodeType type) {

  public FlowNode {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(type, "type must not be null");
  }
}
