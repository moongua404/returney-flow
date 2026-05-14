package com.returney.flow.domain.definition;

/** 플로우 노드 유형. */
public enum NodeType {
  /** LLM API 호출 노드. */
  LLM,
  /** 프롬프트 템플릿 렌더링 노드 (PromptRenderer.render()). */
  TEMPLATE,
  /** 서버 커스텀 노드: Map → List&lt;String&gt; 팬아웃. */
  SCATTER,
  /** 서버 커스텀 노드: List&lt;String&gt; → String 수렴. */
  GATHER,
  /** 서버 커스텀 노드: Map → String 변환. */
  TRANSFORM,
  /**
   * 조건부 게이트. {@code inputs.condition}을 평가하여:
   * <ul>
   *   <li>"true" (대소문자 무관) → 게이트 통과 (COMPLETED), 다운스트림 진행
   *   <li>그 외(false/빈값/null/임의의 다른 값) → 게이트 차단 (SKIPPED), 다운스트림 자동 skip
   * </ul>
   *
   * <p>호출 시점 분기 처리(특정 prereq에 따라 일부 노드만 활성화)를 yaml DAG로 표현하기 위한
   * 노드 타입. 호출자가 외부에서 nodeIds 마스킹할 필요 없음.
   */
  CONDITIONAL
}
