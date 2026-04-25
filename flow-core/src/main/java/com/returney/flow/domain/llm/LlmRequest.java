package com.returney.flow.domain.llm;

import java.util.List;

/**
 * LLM 호출 요청 명세.
 *
 * <p>단일 프롬프트, 대화형(멀티턴 캐싱), 멀티모달(바이너리 첨부) 세 가지 모드를 지원한다.
 */
public record LlmRequest(
    String model,
    int thinkingBudget,
    String singlePrompt,
    String systemPrompt,
    List<Message> messages,
    CacheConfig cache,
    byte[] binaryContent,
    String mimeType) {

  public record Message(String role, String content) {}

  public record CacheConfig(boolean enabled) {}

  /** 단일 프롬프트 모드. */
  public static LlmRequest single(String prompt, String model, int thinkingBudget) {
    return new LlmRequest(model, thinkingBudget, prompt, null, null, null, null, null);
  }

  /** 대화 모드 (system + messages 분리, 캐싱 가능). */
  public static LlmRequest conversation(
      String systemPrompt, List<Message> messages, String model, CacheConfig cache) {
    return new LlmRequest(model, 0, null, systemPrompt, messages, cache, null, null);
  }

  /** 대화 모드 (thinkingBudget 지정). */
  public static LlmRequest conversation(
      String systemPrompt, List<Message> messages, String model, int thinkingBudget, CacheConfig cache) {
    return new LlmRequest(model, thinkingBudget, null, systemPrompt, messages, cache, null, null);
  }

  /** 멀티모달 모드 (텍스트 + 바이너리 첨부). */
  public static LlmRequest multimodal(
      String textPrompt, byte[] binaryContent, String mimeType, String model, int thinkingBudget) {
    return new LlmRequest(model, thinkingBudget, textPrompt, null, null, null, binaryContent, mimeType);
  }

  public boolean isConversation() {
    return messages != null && systemPrompt != null;
  }

  public boolean isMultimodal() {
    return binaryContent != null;
  }
}
