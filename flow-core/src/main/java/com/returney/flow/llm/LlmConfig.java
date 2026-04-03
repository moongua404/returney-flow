package com.returney.flow.llm;

/**
 * LLM 실행 설정.
 *
 * @param apiKey Gemini API 키
 * @param defaultModel 기본 모델 (예: "gemini-2.0-flash")
 * @param baseUrl Gemini API base URL
 * @param temperature 생성 온도 (0.0 ~ 2.0)
 * @param maxOutputTokens 최대 출력 토큰
 */
public record LlmConfig(
    String apiKey,
    String defaultModel,
    String baseUrl,
    double temperature,
    int maxOutputTokens) {

  /** 기본 설정으로 생성. API 키만 필수. */
  public static LlmConfig of(String apiKey) {
    return new LlmConfig(
        apiKey,
        "gemini-2.0-flash",
        "https://generativelanguage.googleapis.com/v1beta/models",
        0.7,
        8192);
  }

  /** 모델을 지정하여 생성. */
  public static LlmConfig of(String apiKey, String defaultModel) {
    return new LlmConfig(
        apiKey,
        defaultModel,
        "https://generativelanguage.googleapis.com/v1beta/models",
        0.7,
        8192);
  }
}
