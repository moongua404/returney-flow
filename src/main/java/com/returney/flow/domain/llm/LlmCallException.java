package com.returney.flow.domain.llm;

/**
 * LLM API 호출 실패 시 발생하는 예외 계층의 base.
 *
 * <p>서브타입:
 * <ul>
 *   <li>{@link LlmClientErrorException} — HTTP 4xx (재시도 불가)</li>
 *   <li>{@link LlmTransientException} — HTTP 429/500/503 (재시도 가능)</li>
 *   <li>{@link LlmNetworkException} — IO/인터럽트</li>
 * </ul>
 */
public class LlmCallException extends RuntimeException {

  public LlmCallException(String message) {
    super(message);
  }

  public LlmCallException(String message, Throwable cause) {
    super(message, cause);
  }

  /** HTTP 비성공 응답을 statusCode에 따라 적절한 서브타입으로 변환한다. */
  public static LlmCallException fromHttpError(String provider, int statusCode, String body) {
    if (statusCode == 429 || statusCode == 500 || statusCode == 503) {
      return new LlmTransientException(provider, statusCode, body);
    }
    return new LlmClientErrorException(provider, statusCode, body);
  }
}
