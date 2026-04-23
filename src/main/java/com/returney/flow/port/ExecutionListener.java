package com.returney.flow.port;

import com.returney.flow.domain.execution.PipelineResult;
import com.returney.flow.domain.execution.NodeResult;

/**
 * 플로우 실행 이벤트 리스너.
 *
 * <p>실행 엔진이 노드 시작/완료/실패 시 호출한다. 호출자가 SSE, 로깅 등으로 구현한다.
 */
public interface ExecutionListener {

  /** 노드 실행이 시작되었을 때 호출된다. */
  void onNodeStarted(String nodeId, long timestamp);

  /** 노드 실행이 성공적으로 완료되었을 때 호출된다. */
  void onNodeCompleted(String nodeId, NodeResult result);

  /** 노드 실행이 실패했을 때 호출된다. */
  void onNodeFailed(String nodeId, String error);

  /** 업스트림 실패로 노드가 건너뛰어졌을 때 호출된다. */
  void onNodeSkipped(String nodeId);

  /** 전체 플로우 실행이 완료되었을 때 호출된다. */
  void onFlowCompleted(PipelineResult result);
}
