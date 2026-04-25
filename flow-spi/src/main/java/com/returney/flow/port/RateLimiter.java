package com.returney.flow.port;

/**
 * Rate limiting 추상화. 모델별 슬라이딩 윈도우 등 다양한 구현이 가능하다.
 *
 * <p>호출자는 LLM 요청 직전 {@link #acquire}로 슬롯을 획득하고, 응답 수신 후
 * 반환받은 {@link Reservation#confirm}으로 실제 토큰 수를 보정한다.
 *
 * <p><b>계약</b>: 한 acquire에 대해 confirm은 정확히 1회 호출되어야 한다.
 * 호출 실패로 confirm을 못 부르더라도 estimated 토큰은 윈도우에 카운트된 채
 * 60초 후 자연 만료된다.
 */
public interface RateLimiter {

  /**
   * 슬롯을 획득한다. 한도 초과 시 블로킹.
   *
   * @param model 라우팅 결정 후 확정된 모델명
   * @param estimatedTokens 요청의 추정 토큰 수
   * @return 보정용 핸들. 정확히 1회 {@link Reservation#confirm}을 호출해야 한다.
   */
  Reservation acquire(String model, int estimatedTokens) throws InterruptedException;

  /** acquire 1회에 대응하는 보정 핸들. 일회성. */
  interface Reservation {
    /** 응답 수신 후 실제 토큰 수로 보정. 두 번 호출하면 두 번째는 무시된다. */
    void confirm(int actualTokens);
  }

  /** 한도 없음 — 모든 acquire가 즉시 통과하고 confirm은 no-op. */
  static RateLimiter unlimited() {
    Reservation noop = actualTokens -> {};
    return (model, est) -> noop;
  }
}
