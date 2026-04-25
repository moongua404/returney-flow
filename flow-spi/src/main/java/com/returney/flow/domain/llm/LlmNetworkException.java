package com.returney.flow.domain.llm;

/** LLM 호출 중 네트워크/IO 오류 또는 스레드 인터럽트가 발생한 경우. */
public class LlmNetworkException extends LlmCallException {

  public LlmNetworkException(String message, Throwable cause) {
    super(message, cause);
  }

  /** InterruptedException을 래핑한다. 호출 스레드의 인터럽트 플래그를 복원한다. */
  public static LlmNetworkException interrupted(String provider, InterruptedException e) {
    Thread.currentThread().interrupt();
    return new LlmNetworkException(provider + " 호출 중단", e);
  }

  /** IOException 등 기타 예외를 래핑한다. */
  public static LlmNetworkException wrap(String provider, Exception e) {
    return new LlmNetworkException(provider + " 호출 실패: " + e.getMessage(), e);
  }
}
