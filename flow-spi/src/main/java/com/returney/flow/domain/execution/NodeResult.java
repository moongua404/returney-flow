package com.returney.flow.domain.execution;

/**
 * 노드 실행 결과.
 *
 * @param output LLM 응답 또는 변환 결과 텍스트 (raw)
 * @param typedOutput result.type으로 역직렬화된 Java 객체 (LLM 노드만, null = raw output 그대로)
 * @param latencyMs 실행 소요 시간 (밀리초)
 * @param inputTokens 입력 토큰 수 (LLM 노드만 해당, 변환 노드는 0)
 * @param outputTokens 출력 토큰 수
 */
public record NodeResult(
    String output,
    Object typedOutput,
    long latencyMs,
    int inputTokens,
    int outputTokens) {

  /** 비-LLM 노드용 팩토리. typedOutput 없음. */
  public static NodeResult of(String output, long latencyMs, int inputTokens, int outputTokens) {
    return new NodeResult(output, null, latencyMs, inputTokens, outputTokens);
  }

  /** 변환 노드용 팩토리. 토큰 정보 없음. */
  public static NodeResult ofTransform(String output, long latencyMs) {
    return new NodeResult(output, null, latencyMs, 0, 0);
  }

  /** 변환 노드용 팩토리. typed side output 포함. */
  public static NodeResult ofTransform(String output, Object typedOutput, long latencyMs) {
    return new NodeResult(output, typedOutput, latencyMs, 0, 0);
  }
}
