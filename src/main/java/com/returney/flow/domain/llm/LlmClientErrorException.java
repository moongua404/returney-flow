package com.returney.flow.domain.llm;

/** LLM 프로바이더가 4xx를 반환한 경우. 재시도해도 동일하게 실패한다. */
public class LlmClientErrorException extends LlmCallException {

  private final int statusCode;

  public LlmClientErrorException(String provider, int statusCode, String body) {
    super(provider + " API 클라이언트 오류 " + statusCode + ": " + body);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
