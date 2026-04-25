package com.returney.flow.adapter.common;

import com.returney.flow.adapter.parser.ProvidersConfig.ModelLimits;
import com.returney.flow.port.RateLimiter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RPM/TPM 기반 60초 슬라이딩 윈도우 {@link RateLimiter}.
 *
 * <p>모델별로 독립 윈도우를 보유한다. acquire는 한도 초과 시 블로킹하고,
 * 반환된 {@link Reservation}으로 응답 수신 후 실제 토큰을 보정한다.
 *
 * <p>등록되지 않은 모델은 무제한 통과 (1회 stderr 경고). 부정확한 카운팅보다
 * "한도 모름"을 정직하게 알리는 쪽이 안전.
 *
 * <p>Reservation은 thread-safe하지 않다 — confirm은 acquire를 호출한 스레드에서
 * 호출되어야 한다 (LlmNodeRunner의 호출 패턴이 그렇게 되어 있음).
 */
public final class SlidingWindowRateLimiter implements RateLimiter {

  public final AtomicLong waitCount = new AtomicLong();

  private final Map<String, ModelLimits> limits;
  private final ConcurrentHashMap<String, ModelWindow> windows = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Boolean> warnedUnregistered = new ConcurrentHashMap<>();

  public SlidingWindowRateLimiter(Map<String, ModelLimits> limits) {
    this.limits = Map.copyOf(limits);
  }

  @Override
  public Reservation acquire(String model, int estimatedTokens) throws InterruptedException {
    ModelLimits cfg = limits.get(model);
    if (cfg == null) {
      if (warnedUnregistered.putIfAbsent(model, Boolean.TRUE) == null) {
        System.err.println(
            "[RateLimiter] WARN: model '" + model + "' has no rate limit configured "
                + "(no entry in providers.yaml models.*.rate). Calls will pass unmetered.");
      }
      return NOOP_RESERVATION;
    }

    int est = Math.max(1, estimatedTokens);
    ModelWindow window = windows.computeIfAbsent(
        model, m -> new ModelWindow(cfg.rpm(), cfg.tpm()));

    boolean waited = false;
    synchronized (window.lock) {
      while (true) {
        window.purge();
        if (window.requestWindow.size() < window.maxRpm
            && window.tokenSum() + est <= window.maxTpm) {
          long now = System.currentTimeMillis();
          long[] entry = new long[]{now, est};
          window.requestWindow.addLast(now);
          window.tokenWindow.addLast(entry);
          return new ReservationImpl(window, entry, est);
        }
        if (!waited) {
          waitCount.incrementAndGet();
          waited = true;
        }
        window.lock.wait(500);
      }
    }
  }

  // ── internals ──

  private static final Reservation NOOP_RESERVATION = actualTokens -> {};

  private static final class ReservationImpl implements Reservation {
    private final ModelWindow window;
    private final long[] entry;        // 자기가 추가한 [timestamp, tokenCount] 핸들
    private final int estimated;
    private boolean consumed = false;

    ReservationImpl(ModelWindow window, long[] entry, int estimated) {
      this.window = window;
      this.entry = entry;
      this.estimated = estimated;
    }

    @Override
    public void confirm(int actualTokens) {
      if (consumed) return;            // 일회성 보장 — 재호출은 무시
      consumed = true;
      int actual = Math.max(0, actualTokens);
      synchronized (window.lock) {
        entry[1] = actual;
        if (actual < estimated) window.lock.notifyAll();   // 토큰 풀려서 대기 자 깨움
      }
    }
  }

  private static final class ModelWindow {
    final int maxRpm;
    final long maxTpm;
    final Deque<Long> requestWindow = new ArrayDeque<>();
    final Deque<long[]> tokenWindow = new ArrayDeque<>();
    final Object lock = new Object();

    ModelWindow(int maxRpm, long maxTpm) {
      this.maxRpm = maxRpm;
      this.maxTpm = maxTpm;
    }

    void purge() {
      long cutoff = System.currentTimeMillis() - 60_000;
      while (!requestWindow.isEmpty() && requestWindow.peekFirst() < cutoff) {
        requestWindow.pollFirst();
      }
      while (!tokenWindow.isEmpty() && tokenWindow.peekFirst()[0] < cutoff) {
        tokenWindow.pollFirst();
      }
    }

    long tokenSum() {
      long sum = 0;
      for (long[] entry : tokenWindow) sum += entry[1];
      return sum;
    }
  }
}
