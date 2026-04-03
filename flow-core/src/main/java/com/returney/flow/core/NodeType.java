package com.returney.flow.core;

/** 플로우 노드 유형. */
public enum NodeType {
  /** LLM API 호출이 필요한 노드. */
  LLM,
  /** 데이터 변환/집계 등 LLM 없이 처리하는 노드. */
  TRANSFORM
}
