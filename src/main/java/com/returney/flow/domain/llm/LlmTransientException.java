package com.returney.flow.domain.llm;

/** LLM 프로바이더가 429/500/503을 반환한 경우. HttpUtil 재시도 소진 후 도달한다. */
public class LlmTransientException extends LlmCallException {

  private final int statusCode;

  public LlmTransientException(String provider, int statusCode, String body) {
    super(provider + " API 일시적 오류 " + statusCode + ": " + body);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
