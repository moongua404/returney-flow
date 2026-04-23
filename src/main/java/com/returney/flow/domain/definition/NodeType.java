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
  TRANSFORM
}
