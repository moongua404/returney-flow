package com.returney.flow.domain.llm;

/** LLM API 호출의 원시 응답. 프로바이더별 파싱 결과를 통일된 형태로 반환. */
public record LlmRawResponse(
    String text,
    int inputTokens,
    int outputTokens,
    int thinkingTokens,
    int cacheCreationTokens,
    int cacheReadTokens) {

  public int totalTokens() {
    return inputTokens + outputTokens + thinkingTokens + cacheCreationTokens + cacheReadTokens;
  }
}
