package com.returney.flow.adapter.provider.gemini;

/**
 * Gemini LLM 실행 설정.
 *
 * @param apiKey Gemini API 키
 * @param defaultModel 기본 모델 (예: "gemini-2.5-flash")
 * @param baseUrl Gemini API base URL
 * @param temperature 생성 온도 (0.0 ~ 2.0)
 * @param maxOutputTokens 최대 출력 토큰
 */
public record GeminiConfig(
    String apiKey,
    String defaultModel,
    String baseUrl,
    double temperature,
    int maxOutputTokens) {

  static final String DEFAULT_MODEL = "gemini-2.5-flash";

  public static GeminiConfig of(String apiKey) {
    return new GeminiConfig(
        apiKey,
        DEFAULT_MODEL,
        "https://generativelanguage.googleapis.com/v1beta/models",
        0.7,
        8192);
  }

  public static GeminiConfig of(String apiKey, String defaultModel) {
    return new GeminiConfig(
        apiKey,
        defaultModel,
        "https://generativelanguage.googleapis.com/v1beta/models",
        0.7,
        8192);
  }
}
