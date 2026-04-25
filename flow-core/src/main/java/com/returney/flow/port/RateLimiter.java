package com.returney.flow.port;

/**
 * Rate limiting 정책 추상화.
 *
 * <p>싱글톤으로 운용되며 모델별 슬라이딩 윈도우를 내부에서 관리한다.
 * 구현체는 {@link com.returney.flow.adapter.common.SlidingWindowRateLimiter}.
 *
 * <p>등록되지 않은 모델명을 전달하면 {@link IllegalArgumentException}이 발생한다.
 * sessionId는 현재 구현체에서 무시되며, 향후 퍼-세션 추적/로깅용으로 계약에 포함된다.
 */
public interface RateLimiter {

  /** 요청 슬롯을 획득한다. 한도 초과 시 블로킹. 미등록 모델이면 즉시 예외. */
  void acquire(String model, String sessionId, int estimatedTokens) throws InterruptedException;

  /** LLM 응답 수신 후 실제 토큰 수로 보정한다. */
  void correct(String model, String sessionId, int actual);
}
