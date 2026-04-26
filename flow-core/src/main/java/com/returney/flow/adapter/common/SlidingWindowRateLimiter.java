package com.returney.flow.adapter.common;

import com.returney.flow.adapter.parser.ProvidersConfig.ModelLimits;
import com.returney.flow.port.RateLimiter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RPM/TPM 기반 60초 슬라이딩 윈도우 {@link RateLimiter}.
 *
 * <p>모델별로 독립 윈도우를 보유한다. acquire는 한도 초과 시 블로킹하고,
 * 반환된 {@link Reservation}으로 응답 수신 후 실제 토큰을 보정한다.
 *
 * <p>등록되지 않은 모델은 무제한 통과 (1회 stderr 경고). 부정확한 카운팅보다
 * "한도 모름"을 정직하게 알리는 쪽이 안전.
 *
 * <p>{@link ReentrantLock} + {@link Condition} 기반. virtual thread 친화적
 * (synchronized pinning 회피, JDK 21에서 의미 있음). 슬롯이 자연 만료로
 * 풀리는 케이스는 윈도우 head 만료까지의 정확한 시간만큼 awaitNanos로 대기 →
 * 폴링 없이도 정확한 깨어남.
 *
 * <p>Reservation은 thread-safe — {@code confirm}은 호출 스레드와 무관하게
 * AtomicBoolean으로 일회성 보장.
 */
public final class SlidingWindowRateLimiter implements RateLimiter {

  private static final long WINDOW_MS = 60_000;

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
    window.lock.lock();
    try {
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
        // 다음 자연 만료까지의 정확한 시간 대기 → 폴링 0.
        // confirm()이 시그널을 보내면 그 전이라도 깨어남.
        long nextExpiryMs = window.nextExpiryMs();
        long now = System.currentTimeMillis();
        long waitMs = Math.max(1, nextExpiryMs - now);
        window.condition.awaitNanos(TimeUnit.MILLISECONDS.toNanos(waitMs));
      }
    } finally {
      window.lock.unlock();
    }
  }

  // ── internals ──

  private static final Reservation NOOP_RESERVATION = actualTokens -> {};

  private static final class ReservationImpl implements Reservation {
    private final ModelWindow window;
    private final long[] entry;        // 자기가 추가한 [timestamp, tokenCount] 핸들
    private final int estimated;
    private final java.util.concurrent.atomic.AtomicBoolean consumed =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    ReservationImpl(ModelWindow window, long[] entry, int estimated) {
      this.window = window;
      this.entry = entry;
      this.estimated = estimated;
    }

    @Override
    public void confirm(int actualTokens) {
      // CAS로 일회성 보장 — 다른 스레드에서 재호출되거나 acquire 스레드 외부에서
      // 호출돼도 race 없이 한 번만 처리.
      if (!consumed.compareAndSet(false, true)) return;
      int actual = Math.max(0, actualTokens);
      window.lock.lock();
      try {
        entry[1] = actual;
        if (actual < estimated) window.condition.signalAll();   // 토큰 풀려서 대기자 깨움
      } finally {
        window.lock.unlock();
      }
    }
  }

  private static final class ModelWindow {
    final int maxRpm;
    final long maxTpm;
    final Deque<Long> requestWindow = new ArrayDeque<>();
    final Deque<long[]> tokenWindow = new ArrayDeque<>();
    final ReentrantLock lock = new ReentrantLock();
    final Condition condition = lock.newCondition();

    ModelWindow(int maxRpm, long maxTpm) {
      this.maxRpm = maxRpm;
      this.maxTpm = maxTpm;
    }

    void purge() {
      long cutoff = System.currentTimeMillis() - WINDOW_MS;
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

    /** 윈도우 head가 자연 만료될 정확한 timestamp(ms). 비어 있으면 1초 후. */
    long nextExpiryMs() {
      long head = -1;
      if (!requestWindow.isEmpty()) head = requestWindow.peekFirst();
      if (!tokenWindow.isEmpty()) {
        long t = tokenWindow.peekFirst()[0];
        head = head < 0 ? t : Math.min(head, t);
      }
      if (head < 0) return System.currentTimeMillis() + 1000;
      return head + WINDOW_MS + 1;   // +1ms safety: cutoff strict less-than
    }
  }
}
