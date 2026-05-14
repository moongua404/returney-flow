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
 * @param resultType 출력 Java 타입 FQCN. null 허용 — 중간 노드(scatter/gather 페어 사이의 fan-out
 *     이나 호출자가 결과를 따로 안 받는 노드)는 result.type을 yaml에서 생략한다.
 *     코드젠은 resultType이 있는 노드만 결과 record/타입 컴포넌트로 포함한다.
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
    inputs = inputs != null ? Map.copyOf(inputs) : Map.of();
  }
}
