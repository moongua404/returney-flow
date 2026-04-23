package com.returney.flow.domain.definition;

import java.util.Objects;

/**
 * 파이프라인 DAG의 엣지 (데이터 의존성).
 *
 * @param from 소스 노드 ID
 * @param to 대상 노드 ID
 */
public record PipelineEdge(String from, String to) {

  public PipelineEdge {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
  }
}
