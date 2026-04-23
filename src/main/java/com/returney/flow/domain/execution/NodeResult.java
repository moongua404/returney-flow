package com.returney.flow.domain.execution;

/**
 * 노드 실행 결과.
 *
 * @param output LLM 응답 또는 변환 결과 텍스트
 * @param latencyMs 실행 소요 시간 (밀리초)
 * @param inputTokens 입력 토큰 수 (LLM 노드만 해당, 변환 노드는 0)
 * @param outputTokens 출력 토큰 수
 */
public record NodeResult(String output, long latencyMs, int inputTokens, int outputTokens) {

  /** 변환 노드용 팩토리. 토큰 정보 없음. */
  public static NodeResult ofTransform(String output, long latencyMs) {
    return new NodeResult(output, latencyMs, 0, 0);
  }
}
