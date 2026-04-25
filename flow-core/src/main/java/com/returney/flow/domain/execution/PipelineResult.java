package com.returney.flow.domain.execution;

import java.util.List;
import java.util.Map;

/**
 * 전체 파이프라인 실행 결과.
 *
 * @param totalLatencyMs 총 실행 소요 시간 (밀리초)
 * @param nodeResults 노드별 실행 결과
 * @param failedNodes 실패한 노드 ID 목록
 */
public record PipelineResult(
    long totalLatencyMs, Map<String, NodeResult> nodeResults, List<String> failedNodes) {

  public PipelineResult {
    nodeResults = Map.copyOf(nodeResults);
    failedNodes = List.copyOf(failedNodes);
  }
}
