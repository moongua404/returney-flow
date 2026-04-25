package com.returney.flow.domain.execution;

/** 플로우 실행 중 노드의 상태. */
public enum NodeStatus {
  /** 실행 대기 중. */
  PENDING,
  /** 현재 실행 중. */
  RUNNING,
  /** 실행 성공 완료. */
  COMPLETED,
  /** 실행 실패. */
  FAILED,
  /** 업스트림 실패로 인해 건너뜀. */
  SKIPPED
}
