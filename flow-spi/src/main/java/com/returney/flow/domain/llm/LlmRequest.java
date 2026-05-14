package com.returney.flow.domain.llm;

/**
 * LLM 호출 요청 명세.
 *
 * <p>flow는 멀티턴 대화 상태를 인지하지 않는다 — 호출자(백엔드)가 대화 히스토리를 텍스트로
 * 렌더해서 {@code prompt} 한 필드에 담아 넘긴다. provider별 messages 배열 변환은 각
 * executor 내부 책임.
 */
public record LlmRequest(
    String model,
    int thinkingBudget,
    String systemPrompt,
    String prompt,
    boolean cacheEnabled,
    byte[] binaryContent,
    String mimeType) {

  /** system/binary 없는 평범한 텍스트 호출용 단축. */
  public static LlmRequest text(String prompt, String model, int thinkingBudget) {
    return new LlmRequest(model, thinkingBudget, null, prompt, false, null, null);
  }

  public boolean isMultimodal() {
    return binaryContent != null;
  }
}
