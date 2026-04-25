package com.returney.flow.application;

import com.returney.flow.adapter.common.SlidingWindowRateLimiter;
import com.returney.flow.adapter.parser.ProvidersConfig;
import com.returney.flow.adapter.parser.ProvidersConfig.RetryPolicy;
import com.returney.flow.adapter.parser.ProvidersYamlParser;
import com.returney.flow.adapter.provider.anthropic.ClaudeLlmExecutor;
import com.returney.flow.adapter.provider.gemini.GeminiConfig;
import com.returney.flow.adapter.provider.gemini.GeminiLlmExecutor;
import com.returney.flow.adapter.provider.openai.GptLlmExecutor;
import com.returney.flow.adapter.provider.openai.ReasoningLlmExecutor;
import com.returney.flow.domain.llm.LlmCallEvent;
import com.returney.flow.domain.llm.LlmCallException;
import com.returney.flow.domain.llm.LlmNetworkException;
import com.returney.flow.domain.llm.LlmRawResponse;
import com.returney.flow.domain.llm.LlmRequest;
import com.returney.flow.domain.llm.LlmTransientException;
import com.returney.flow.port.ApiKeySupplier;
import com.returney.flow.port.ExecutionListener;
import com.returney.flow.port.LlmExecutor;
import com.returney.flow.port.RateLimiter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 모델명을 기반으로 LLM 프로바이더를 라우팅하는 라우터.
 *
 * <p>flow-core 내부 구현. 코드젠이 만든 *PipelineBase가 자동 사용.
 *
 * <p>각 호출은 다음 단계를 거친다:
 * <ol>
 *   <li>모델 → 프로바이더 라우팅 + capability 보정 (thinking budget cap)
 *   <li>{@link RateLimiter#acquire} 슬롯 획득 (한도 초과 시 블로킹)
 *   <li>프로바이더 executor.execute() 호출
 *   <li>응답으로 {@link RateLimiter.Reservation#confirm} 보정
 *   <li>{@link ExecutionListener#onLlmCall} 이벤트 발행 (성공/실패 모두)
 *   <li>실패 시 fallback 모델 1단 재시도 (위 단계 반복, attemptIndex=1)
 * </ol>
 */
public final class InternalLlmRouter implements LlmExecutor {

  /** 테스트 가능성을 위한 sleep 추상. 디폴트는 {@link Thread#sleep}. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(long ms) throws InterruptedException;

    static Sleeper real() {
      return Thread::sleep;
    }
  }

  private final ProvidersConfig config;
  private final Map<String, LlmExecutor> executors;
  private final RateLimiter rateLimiter;
  private final Sleeper sleeper;

  // 컨텍스트는 호출 스레드별로 보관. virtual thread fan-out에선 호출 직전 setX가 매번 일어남.
  private final ThreadLocal<UUID> currentSessionId = new ThreadLocal<>();
  private final ThreadLocal<String> currentAction = new ThreadLocal<>();
  private final ThreadLocal<ExecutionListener> currentListener =
      ThreadLocal.withInitial(ExecutionListener::noop);

  private InternalLlmRouter(
      ProvidersConfig config,
      Map<String, LlmExecutor> executors,
      RateLimiter rateLimiter,
      Sleeper sleeper) {
    this.config = config;
    this.executors = executors;
    this.rateLimiter = rateLimiter;
    this.sleeper = sleeper;
  }

  /** 테스트 전용 — 명시적 executor 맵 + 한도 없는 limiter + 즉시 반환 sleeper. */
  static InternalLlmRouter forTesting(ProvidersConfig config, Map<String, LlmExecutor> executors) {
    return new InternalLlmRouter(config, new HashMap<>(executors), RateLimiter.unlimited(), ms -> {});
  }

  /** 테스트 전용 — 명시적 executor 맵 + 명시적 limiter + 즉시 반환 sleeper. */
  static InternalLlmRouter forTesting(
      ProvidersConfig config, Map<String, LlmExecutor> executors, RateLimiter rateLimiter) {
    return new InternalLlmRouter(config, new HashMap<>(executors), rateLimiter, ms -> {});
  }

  /** 테스트 전용 — sleeper 주입까지 명시. backoff 호출 횟수/지연을 검증할 때 사용. */
  static InternalLlmRouter forTesting(
      ProvidersConfig config,
      Map<String, LlmExecutor> executors,
      RateLimiter rateLimiter,
      Sleeper sleeper) {
    return new InternalLlmRouter(config, new HashMap<>(executors), rateLimiter, sleeper);
  }

  /** 클래스패스 {@code providers.yaml} + 환경변수 키로 라우터 생성. */
  public static InternalLlmRouter fromClasspath() {
    ProvidersConfig config = ProvidersYamlParser.loadFromClasspath();
    return from(config, ApiKeySupplier.fromEnv(),
        new SlidingWindowRateLimiter(config.rateLimits()));
  }

  /** 명시적 키 공급자 + 디폴트 SlidingWindowRateLimiter로 라우터 생성. */
  public static InternalLlmRouter from(ProvidersConfig config, ApiKeySupplier keys) {
    return from(config, keys, new SlidingWindowRateLimiter(config.rateLimits()));
  }

  /** 명시적 키 공급자 + 명시적 RateLimiter로 라우터 생성. */
  public static InternalLlmRouter from(
      ProvidersConfig config, ApiKeySupplier keys, RateLimiter rateLimiter) {
    Map<String, LlmExecutor> executors = new HashMap<>();
    ProvidersConfig.HttpDefaults defaults = config.defaults();
    int connectSec = defaults.connectTimeoutSec();
    int requestSec = defaults.requestTimeoutSec();
    for (var entry : config.providers().entrySet()) {
      String providerName = entry.getKey();
      ProvidersConfig.ProviderEntry p = entry.getValue();
      String key = keys.get(p.apiKeyName());
      if (key == null || key.isBlank()) {
        // 키 없는 프로바이더는 등록하지 않음 — 해당 모델 호출 시 명확한 에러 발생
        continue;
      }
      LlmExecutor exec = buildExecutor(p, key, connectSec, requestSec);
      if (exec != null) executors.put(providerName, exec);
    }
    return new InternalLlmRouter(config, executors, rateLimiter, Sleeper.real());
  }

  private static LlmExecutor buildExecutor(
      ProvidersConfig.ProviderEntry p, String apiKey, int connectSec, int requestSec) {
    return switch (p.type()) {
      case "gemini" -> new GeminiLlmExecutor(GeminiConfig.of(apiKey), connectSec, requestSec);
      case "anthropic" -> new ClaudeLlmExecutor(apiKey, p.baseUrl(), connectSec, requestSec);
      case "openai" -> new GptLlmExecutor(apiKey, p.baseUrl(), connectSec, requestSec);
      case "openai-reasoning" -> new ReasoningLlmExecutor(apiKey, p.baseUrl(), connectSec, requestSec);
      default -> null;
    };
  }

  @Override
  public void setSessionId(UUID sessionId) {
    currentSessionId.set(sessionId);
  }

  @Override
  public void setContext(String action, Map<String, String> variables) {
    currentAction.set(action);
  }

  @Override
  public void setLifecycle(ExecutionListener listener) {
    currentListener.set(listener != null ? listener : ExecutionListener.noop());
  }

  @Override
  public LlmRawResponse execute(LlmRequest request) throws LlmCallException {
    String model = request.model();
    if (model == null || model.isBlank()) {
      model = config.defaultModel();
    }

    Ctx ctx = new Ctx(
        currentSessionId.get() != null ? currentSessionId.get().toString() : null,
        currentAction.get(),
        currentListener.get());

    LlmRequest first = applyCapability(ensureModel(request, model), model);
    try {
      return attemptWithRetries(first, model, ctx, 0);
    } catch (LlmCallException primaryError) {
      String fallbackModel = config.fallbackFor(model);
      if (fallbackModel == null || !isTransient(primaryError)) {
        throw primaryError;
      }
      LlmRequest fbRequest = applyCapability(ensureModel(request, fallbackModel), fallbackModel);
      // primary가 N회 시도 후 fallback 시작 — attemptIndex offset 적용
      return attemptWithRetries(fbRequest, fallbackModel, ctx, config.retryPolicy().maxAttempts());
    }
  }

  /**
   * 같은 모델로 retryPolicy.maxAttempts() 회까지 재시도.
   *
   * <p>{@link LlmTransientException} / {@link LlmNetworkException}만 재시도 대상.
   * permanent 오류 (LlmClientErrorException 등)는 즉시 propagate.
   *
   * <p>{@link LlmTransientException#retryAfterMs()}가 있으면 exp backoff와 max를 취해
   * 서버 권고를 존중한다 (RFC 7231 Retry-After).
   *
   * @param baseAttemptIndex onLlmCall 이벤트의 attemptIndex 시작값
   */
  private LlmRawResponse attemptWithRetries(
      LlmRequest request, String model, Ctx ctx, int baseAttemptIndex) throws LlmCallException {
    RetryPolicy policy = config.retryPolicy();
    LlmCallException lastTransient = null;
    for (int i = 0; i < policy.maxAttempts(); i++) {
      try {
        return attempt(request, model, ctx, baseAttemptIndex + i);
      } catch (LlmCallException e) {
        if (!isTransient(e)) throw e;             // permanent → propagate
        lastTransient = e;
        if (i + 1 < policy.maxAttempts()) {
          sleepBackoff(policy, i, e);
        }
      }
    }
    throw lastTransient;                            // 모든 시도 소진
  }

  private LlmRawResponse attempt(
      LlmRequest request, String model, Ctx ctx, int attemptIndex) throws LlmCallException {
    String providerName = config.resolveProvider(model);
    if (providerName == null) {
      throw new LlmCallException(
          "No provider matches model: " + model + " (check providers.yaml routing)");
    }
    LlmExecutor exec = executors.get(providerName);
    if (exec == null) {
      throw new LlmCallException(
          "Provider " + providerName + " is not registered "
              + "(missing API key for model=" + model + ")");
    }

    int estimated = estimateTokens(request);
    RateLimiter.Reservation reservation;
    try {
      reservation = rateLimiter.acquire(model, estimated);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new LlmCallException("Rate limit acquire interrupted for model=" + model, ie);
    }

    long start = System.currentTimeMillis();
    try {
      LlmRawResponse response = exec.execute(request);
      long latency = System.currentTimeMillis() - start;
      reservation.confirm(response.totalTokens());
      safelyEmit(ctx.listener,
          LlmCallEvent.success(ctx.sessionId, ctx.action, request, response, model, latency, attemptIndex));
      return response;
    } catch (RuntimeException e) {
      long latency = System.currentTimeMillis() - start;
      // 실패 시에도 reservation 해소 (estimate 그대로 카운트되도록).
      reservation.confirm(estimated);
      safelyEmit(ctx.listener,
          LlmCallEvent.failure(ctx.sessionId, ctx.action, request, model, latency, e, attemptIndex));
      throw e;
    }
  }

  /**
   * 요청의 추정 토큰 수. ~4자=1토큰의 거친 휴리스틱. acquire 시 윈도우 점유분으로 사용되고
   * confirm 시 실제 totalTokens로 보정된다.
   */
  private static int estimateTokens(LlmRequest request) {
    int chars = 0;
    if (request.singlePrompt() != null) chars += request.singlePrompt().length();
    if (request.systemPrompt() != null) chars += request.systemPrompt().length();
    if (request.messages() != null) {
      for (LlmRequest.Message m : request.messages()) chars += m.content().length();
    }
    return Math.max(1, chars / 4);
  }

  /**
   * Transient(재시도 가능) 오류 분류.
   *
   * <p>{@link LlmTransientException} (429/500/503) 또는 {@link LlmNetworkException} (IO/timeout)만
   * 재시도/fallback 대상. 4xx auth/bad request 같은 항구적 오류는 false → 즉시 throw.
   */
  private static boolean isTransient(Throwable e) {
    return e instanceof LlmTransientException || e instanceof LlmNetworkException;
  }

  /**
   * retryIdx (0=첫 재시도)마다 exponential backoff + jitter로 sleep.
   *
   * <p>마지막 오류가 {@link LlmTransientException}이고 Retry-After 헤더가 있었으면
   * {@code max(expBackoff, retryAfterMs)}를 적용한다 (서버 권고 존중).
   * 단, jitter는 자체 backoff에만 적용 — Retry-After는 정확한 시간을 의도하므로 jitter 없음.
   */
  private void sleepBackoff(RetryPolicy policy, int retryIdx, LlmCallException lastError)
      throws LlmCallException {
    long expDelay = computeExpBackoff(policy, retryIdx);
    long retryAfter = 0L;
    if (lastError instanceof LlmTransientException tre) {
      retryAfter = tre.retryAfterMs().orElse(0L);
    }
    long delay = Math.max(expDelay, retryAfter);
    try {
      sleeper.sleep(delay);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new LlmCallException("Retry backoff interrupted", ie);
    }
  }

  /** exp backoff + jitter 계산. Retry-After와 무관한 자체 정책 부분. */
  private static long computeExpBackoff(RetryPolicy policy, int retryIdx) {
    double base = policy.initialDelayMs() * Math.pow(policy.backoffMultiplier(), retryIdx);
    long delay = Math.min((long) base, policy.maxDelayMs());
    if (policy.jitter() > 0.0) {
      double factor = 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * policy.jitter();
      delay = Math.max(0L, (long) (delay * factor));
    }
    return delay;
  }

  /** ThreadLocal 컨텍스트를 호출당 1회 읽어 retry/fallback 동안 안정적으로 전달. */
  private record Ctx(String sessionId, String action, ExecutionListener listener) {}

  /**
   * capability에 따라 thinkingBudget 보정. 미지원 모델은 0으로 강제, 지원 모델은
   * maxBudget을 상한으로 cap.
   */
  private LlmRequest applyCapability(LlmRequest request, String model) {
    ProvidersConfig.ModelCapability cap = config.capability(model);
    int budget = request.thinkingBudget();
    int adjusted;
    if (!cap.supportsThinking()) {
      adjusted = 0;
    } else if (cap.thinkingMaxBudget() > 0 && budget > cap.thinkingMaxBudget()) {
      adjusted = cap.thinkingMaxBudget();
    } else {
      adjusted = budget;
    }
    if (adjusted == budget) return request;
    return new LlmRequest(
        request.model(),
        adjusted,
        request.singlePrompt(),
        request.systemPrompt(),
        request.messages(),
        request.cache(),
        request.binaryContent(),
        request.mimeType());
  }

  /** 리스너 콜백에서 던진 예외가 호출 결과를 가리지 않도록 격리. */
  private static void safelyEmit(ExecutionListener listener, LlmCallEvent event) {
    try {
      listener.onLlmCall(event);
    } catch (RuntimeException ignored) {
      // 리스너 결함이 LLM 호출 결과를 침범하지 않도록 swallow
    }
  }

  /** request.model이 비어있어 default로 보강된 경우 새 LlmRequest 발급. */
  private static LlmRequest ensureModel(LlmRequest request, String resolved) {
    if (resolved.equals(request.model())) return request;
    return new LlmRequest(
        resolved,
        request.thinkingBudget(),
        request.singlePrompt(),
        request.systemPrompt(),
        request.messages(),
        request.cache(),
        request.binaryContent(),
        request.mimeType());
  }
}
