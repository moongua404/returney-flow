package com.returney.flow.domain.llm;

import java.util.Optional;

/**
 * LLM 프로바이더가 429/500/503을 반환한 경우. HttpUtil 재시도 소진 후 도달한다.
 *
 * <p>응답에 {@code Retry-After} 헤더가 있었으면 {@link #retryAfterMs()}로 노출된다.
 * 호출자(InternalLlmRouter)가 다음 재시도 대기 시간을 결정할 때 사용.
 */
public class LlmTransientException extends LlmCallException {

  private final int statusCode;
  private final Long retryAfterMs;

  public LlmTransientException(String provider, int statusCode, String body) {
    this(provider, statusCode, body, null);
  }

  public LlmTransientException(String provider, int statusCode, String body, Long retryAfterMs) {
    super(provider + " API 일시적 오류 " + statusCode + ": " + body);
    this.statusCode = statusCode;
    this.retryAfterMs = retryAfterMs;
  }

  public int statusCode() {
    return statusCode;
  }

  /**
   * 응답에 명시된 {@code Retry-After} 값 (밀리초). 헤더가 없으면 {@code Optional.empty()}.
   * 호출자는 이 값과 자체 backoff 정책의 max를 취하는 것이 권장됨.
   */
  public Optional<Long> retryAfterMs() {
    return Optional.ofNullable(retryAfterMs);
  }
}
