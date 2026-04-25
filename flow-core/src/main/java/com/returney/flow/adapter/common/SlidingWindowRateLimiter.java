package com.returney.flow.adapter.common;

import com.returney.flow.port.RateLimiter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RPM/TPM 기반 슬라이딩 윈도우 {@link RateLimiter} 구현체.
 *
 * <p>60초 윈도우 기준으로 요청 수(RPM)와 토큰 수(TPM)를 제한한다.
 * 싱글톤으로 운용되며 모델별 윈도우를 내부적으로 관리한다.
 * 허용 모델과 한도는 생성자에서 prefix → [rpm, tpm] 맵으로 주입한다.
 */
public class SlidingWindowRateLimiter implements RateLimiter {

  public final AtomicLong waitCount = new AtomicLong();

  private final Map<String, int[]> prefixLimits;
  private final ConcurrentHashMap<String, ModelWindow> windows = new ConcurrentHashMap<>();

  public SlidingWindowRateLimiter(Map<String, int[]> prefixLimits) {
    this.prefixLimits = prefixLimits;
  }

  @Override
  public void acquire(String model, String sessionId, int estimatedTokens)
      throws InterruptedException {
    ModelWindow window = getOrCreate(model);
    boolean waited = false;
    synchronized (window.lock) {
      while (true) {
        window.purge();
        if (window.requestWindow.size() < window.maxRpm
            && window.tokenSum() + estimatedTokens <= window.maxTpm) {
          long now = System.currentTimeMillis();
          window.requestWindow.addLast(now);
          window.tokenWindow.addLast(new long[]{now, estimatedTokens});
          return;
        }
        if (!waited) {
          waitCount.incrementAndGet();
          waited = true;
        }
        window.lock.wait(500);
      }
    }
  }

  @Override
  public void correct(String model, String sessionId, int actual) {
    ModelWindow window = windows.get(model);
    if (window == null) return;
    synchronized (window.lock) {
      if (!window.tokenWindow.isEmpty()) {
        long[] last = window.tokenWindow.peekLast();
        long delta = actual - last[1];
        last[1] = actual;
        if (delta < 0) window.lock.notifyAll();
      }
      window.lock.notifyAll();
    }
  }

  private ModelWindow getOrCreate(String model) {
    return windows.computeIfAbsent(model, m -> {
      int[] limits = resolveLimits(m);
      return new ModelWindow(limits[0], limits[1]);
    });
  }

  private int[] resolveLimits(String model) {
    if (model != null) {
      for (Map.Entry<String, int[]> e : prefixLimits.entrySet()) {
        if (model.startsWith(e.getKey())) return e.getValue();
      }
    }
    throw new IllegalArgumentException(
        "Unregistered model: \"" + model + "\". Add a prefix entry in rate-limits.yaml.");
  }

  private static class ModelWindow {
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
