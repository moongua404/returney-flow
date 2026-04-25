package com.returney.flow.adapter.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.returney.flow.adapter.parser.ProvidersConfig.ModelLimits;
import com.returney.flow.port.RateLimiter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SlidingWindowRateLimiterTest {

  @Test
  void 등록된_모델은_RPM_도달_시_블로킹() throws Exception {
    SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(
        Map.of("m", new ModelLimits(2, 1_000_000)));

    // RPM=2인데 3번째 acquire는 별도 스레드에서 블로킹돼야 함
    var r1 = limiter.acquire("m", 100);
    var r2 = limiter.acquire("m", 100);

    AtomicInteger acquired = new AtomicInteger(0);
    Thread t = new Thread(() -> {
      try {
        limiter.acquire("m", 100);
        acquired.incrementAndGet();
      } catch (InterruptedException ignored) {}
    });
    t.start();
    Thread.sleep(100);

    assertThat(acquired.get()).isZero();
    assertThat(limiter.waitCount.get()).isGreaterThan(0);
    t.interrupt();
    t.join(500);

    r1.confirm(50);
    r2.confirm(50);
  }

  @Test
  void confirm으로_실제토큰_보정_시_TPM_여유_생김() throws Exception {
    // TPM=200인데 estimated 150 두 번이면 한도 초과로 블로킹
    SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(
        Map.of("m", new ModelLimits(100, 200)));

    var r1 = limiter.acquire("m", 150);
    // 첫 호출의 실제 토큰이 50이라고 confirm → TPM 여유 100 → 다음 acquire 통과해야
    r1.confirm(50);

    var r2 = limiter.acquire("m", 100);   // 통과해야 함
    assertThat(r2).isNotNull();
    r2.confirm(100);
  }

  @Test
  void confirm_재호출은_무시() throws Exception {
    SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(
        Map.of("m", new ModelLimits(10, 1000)));

    var r = limiter.acquire("m", 100);
    r.confirm(50);
    r.confirm(999);   // 무시되어야 함 — 두 번째 호출은 윈도우에 영향 없음

    // 이후 acquire는 보정된 50을 반영한 윈도우로 동작
    var r2 = limiter.acquire("m", 950);   // 950+50 = 1000 ≤ TPM
    assertThat(r2).isNotNull();
    r2.confirm(950);
  }

  @Test
  void 모델별_윈도우_격리() throws Exception {
    SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(Map.of(
        "a", new ModelLimits(1, 1000),
        "b", new ModelLimits(1, 1000)));

    // a 모델 슬롯 소진
    var ra = limiter.acquire("a", 100);
    // b 모델은 영향 없이 acquire 통과해야
    var rb = limiter.acquire("b", 100);

    assertThat(ra).isNotNull();
    assertThat(rb).isNotNull();
    ra.confirm(100);
    rb.confirm(100);
  }

  @Test
  void 미등록_모델은_unmetered() throws Exception {
    SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(
        Map.of("known", new ModelLimits(1, 100)));

    // unknown 모델은 RPM=1이지만 무제한이라 여러 번 즉시 통과
    RateLimiter.Reservation r1 = limiter.acquire("unknown", 999);
    RateLimiter.Reservation r2 = limiter.acquire("unknown", 999);
    RateLimiter.Reservation r3 = limiter.acquire("unknown", 999);

    assertThat(r1).isNotNull();
    assertThat(r2).isNotNull();
    assertThat(r3).isNotNull();
    // confirm은 no-op이라도 호출은 가능
    r1.confirm(0);
    r2.confirm(0);
    r3.confirm(0);
  }

  @Test
  void unlimited_헬퍼는_즉시_통과() throws Exception {
    RateLimiter limiter = RateLimiter.unlimited();
    var r = limiter.acquire("any", 999_999);
    assertThat(r).isNotNull();
    r.confirm(0);
  }
}
