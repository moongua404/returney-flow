package com.returney.flow.spi;

import java.util.List;

/**
 * LLM 호출 요청 명세.
 *
 * <p>단일 프롬프트(기존 호환)와 대화형(멀티턴 캐싱) 두 가지 모드를 지원한다.
 */
public record LlmRequest(
    String model,
    int thinkingBudget,
    String singlePrompt,
    String systemPrompt,
    List<Message> messages,
    CacheConfig cache) {

  public record Message(String role, String content) {}

  public record CacheConfig(boolean enabled) {}

  /** 단일 프롬프트 모드 (기존 호환). */
  public static LlmRequest single(String prompt, String model, int thinkingBudget) {
    return new LlmRequest(model, thinkingBudget, prompt, null, null, null);
  }

  /** 대화 모드 (system + messages 분리, 캐싱 가능). */
  public static LlmRequest conversation(
      String systemPrompt, List<Message> messages, String model, CacheConfig cache) {
    return new LlmRequest(model, 0, null, systemPrompt, messages, cache);
  }

  public boolean isConversation() {
    return messages != null && systemPrompt != null;
  }
}
