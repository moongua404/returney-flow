package com.returney.flow.llm;

/**
 * LLM 호출 결과.
 *
 * @param text 응답 텍스트
 * @param model 사용된 모델
 * @param latencyMs 소요 시간 (ms)
 * @param inputTokens 입력 토큰 수 (추정)
 * @param outputTokens 출력 토큰 수 (추정)
 */
public record LlmResponse(
    String text,
    String model,
    long latencyMs,
    int inputTokens,
    int outputTokens) {
}
