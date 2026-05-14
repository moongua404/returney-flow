package com.returney.flow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.returney.flow.adapter.parser.ProvidersConfig;
import com.returney.flow.adapter.parser.ProvidersYamlParser;
import com.returney.flow.domain.execution.NodeResult;
import com.returney.flow.domain.execution.PipelineResult;
import com.returney.flow.domain.llm.LlmCallEvent;
import com.returney.flow.domain.llm.LlmCallException;
import com.returney.flow.domain.llm.LlmClientErrorException;
import com.returney.flow.domain.llm.LlmRawResponse;
import com.returney.flow.domain.llm.LlmRequest;
import com.returney.flow.domain.llm.LlmTransientException;
import com.returney.flow.port.ApiKeySupplier;
import com.returney.flow.port.ExecutionListener;
import com.returney.flow.port.LlmExecutor;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InternalLlmRouterTest {

  private static final ProvidersConfig CONFIG = ProvidersYamlParser.loadFromClasspath();

  @Test
  void 키_없으면_프로바이더_미등록_라우팅_시_명확한_에러() {
    InternalLlmRouter router = InternalLlmRouter.from(CONFIG, name -> null);

    assertThatThrownBy(() ->
            router.execute(LlmRequest.text("hello", "claude-sonnet-4-6", 0), com.returney.flow.domain.llm.LlmCallContext.empty()))
        .isInstanceOf(LlmCallException.class)
        .hasMessageContaining("missing API key");
  }

  @Test
  void 라우팅_매치_없으면_명확한_에러_fallback도_없을_때() {
    // fallback 없는 미니 config — 미매칭 모델이 그대로 에러
    ProvidersConfig configNoFb = ProvidersYamlParser.parse(
        """
        providers:
          gemini:
            type: gemini
            baseUrl: https://example.com
        routing:
          - prefix: "gemini-"
            provider: gemini
        default: gemini-2.5-flash
        """);
    InternalLlmRouter router = InternalLlmRouter.from(configNoFb, fakeKeysFor("gemini"));

    assertThatThrownBy(() ->
            router.execute(LlmRequest.text("x", "weird-model-xyz", 0), com.returney.flow.domain.llm.LlmCallContext.empty()))
        .isInstanceOf(LlmCallException.class)
        .hasMessageContaining("No provider matches");
  }

  @Test
  void model이_비면_default로_보강되어_라우팅() throws Exception {
    AtomicReference<LlmRequest> captured = new AtomicReference<>();

    // gemini 프로바이더 자리에 직접 stub LlmExecutor를 끼울 수 없으므로,
    // default("gemini-2.5-flash") 모델이 gemini로 라우팅된다는 사실만 확인.
    // 실제 호출은 키 누락으로 실패하지만, 라우팅 단계까진 통과해야 함.
    InternalLlmRouter routerNoGemini = InternalLlmRouter.from(CONFIG, name -> null);

    assertThatThrownBy(() ->
            routerNoGemini.execute(LlmRequest.text("hi", null, 0), com.returney.flow.domain.llm.LlmCallContext.empty()))
        .isInstanceOf(LlmCallException.class)
        .hasMessageContaining("Provider gemini")
        .hasMessageContaining("model=gemini-2.5-flash");
  }

  @Test
  void ApiKeySupplier_fromEnv_기본_구현() {
    ApiKeySupplier env = ApiKeySupplier.fromEnv();
    // 환경에 ANTHROPIC_API_KEY 또는 GEMINI_API_KEY가 설정되어 있을 수 있고
    // 없을 수도 있으므로 비-null 가정 없이 호출만 검증.
    String anthropicKey = env.get("anthropic");
    String none = env.get("nonexistent-provider-xyz");
    assertThat(none).isNull();
    // anthropicKey는 환경 따라 null/값 모두 가능 — 호출 자체에 대한 stack trace 없이 끝나는지만 확인
    assertThat(anthropicKey == null || !anthropicKey.isEmpty()).isTrue();
  }

  @Test
  void onLlmCall_성공_이벤트_발행() {
    LlmRawResponse stubResponse = new LlmRawResponse("ok", 10, 5, 15, 0, 0);
    LlmExecutor stub = (req, ctx) -> stubResponse;

    InternalLlmRouter router = InternalLlmRouter.forTesting(CONFIG, Map.of("gemini", stub));

    AtomicReference<LlmCallEvent> captured = new AtomicReference<>();
    ExecutionListener listener = new ExecutionListener() {
      @Override public void onNodeStarted(String n, long t) {}
      @Override public void onNodeCompleted(String n, NodeResult r) {}
      @Override public void onNodeFailed(String n, String e) {}
      @Override public void onNodeSkipped(String n) {}
      @Override public void onFlowCompleted(PipelineResult r) {}
      @Override public void onLlmCall(LlmCallEvent event) { captured.set(event); }
    };

    com.returney.flow.domain.llm.LlmCallContext callCtx =
        new com.returney.flow.domain.llm.LlmCallContext(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "test_action", Map.of("k", "v"), listener);

    LlmRawResponse result = router.execute(LlmRequest.text("hi", "gemini-2.5-flash", 0), callCtx);

    assertThat(result).isSameAs(stubResponse);
    assertThat(captured.get()).isNotNull();
    assertThat(captured.get().success()).isTrue();
    assertThat(captured.get().resolvedModel()).isEqualTo("gemini-2.5-flash");
    assertThat(captured.get().action()).isEqualTo("test_action");
    assertThat(captured.get().sessionId()).isEqualTo("00000000-0000-0000-0000-000000000001");
    assertThat(captured.get().response()).isSameAs(stubResponse);
    assertThat(captured.get().error()).isNull();
    assertThat(captured.get().attemptIndex()).isEqualTo(0);
  }

  @Test
  void onLlmCall_실패_이벤트도_발행() {
    LlmExecutor failing = (req, ctx) -> { throw new LlmCallException("boom", null); };

    InternalLlmRouter router = InternalLlmRouter.forTesting(CONFIG, Map.of("gemini", failing));

    AtomicReference<LlmCallEvent> captured = new AtomicReference<>();
    ExecutionListener listener = new ExecutionListener() {
      @Override public void onNodeStarted(String n, long t) {}
      @Override public void onNodeCompleted(String n, NodeResult r) {}
      @Override public void onNodeFailed(String n, String e) {}
      @Override public void onNodeSkipped(String n) {}
      @Override public void onFlowCompleted(PipelineResult r) {}
      @Override public void onLlmCall(LlmCallEvent event) { captured.set(event); }
    };

    com.returney.flow.domain.llm.LlmCallContext callCtx2 =
        new com.returney.flow.domain.llm.LlmCallContext(null, null, Map.of(), listener);

    assertThatThrownBy(() -> router.execute(LlmRequest.text("x", "gemini-2.5-flash", 0), callCtx2))
        .isInstanceOf(LlmCallException.class)
        .hasMessageContaining("boom");

    assertThat(captured.get()).isNotNull();
    assertThat(captured.get().success()).isFalse();
    assertThat(captured.get().error()).isInstanceOf(LlmCallException.class);
    assertThat(captured.get().response()).isNull();
  }

  @Test
  void 리스너_예외는_LLM_결과를_가리지_않음() {
    LlmRawResponse stubResponse = new LlmRawResponse("ok", 1, 1, 2, 0, 0);
    LlmExecutor stub = (req, ctx) -> stubResponse;

    InternalLlmRouter router = InternalLlmRouter.forTesting(CONFIG, Map.of("gemini", stub));

    ExecutionListener bogusListener = new ExecutionListener() {
      @Override public void onNodeStarted(String n, long t) {}
      @Override public void onNodeCompleted(String n, NodeResult r) {}
      @Override public void onNodeFailed(String n, String e) {}
      @Override public void onNodeSkipped(String n) {}
      @Override public void onFlowCompleted(PipelineResult r) {}
      @Override public void onLlmCall(LlmCallEvent event) { throw new RuntimeException("listener bug"); }
    };

    com.returney.flow.domain.llm.LlmCallContext callCtx3 =
        new com.returney.flow.domain.llm.LlmCallContext(null, null, Map.of(), bogusListener);
    LlmRawResponse result = router.execute(LlmRequest.text("hi", "gemini-2.5-flash", 0), callCtx3);

    assertThat(result).isSameAs(stubResponse);
  }

  @Test
  void capability_미지원_모델은_thinking_budget이_0으로_강제() {
    AtomicReference<LlmRequest> seen = new AtomicReference<>();
    LlmExecutor stub = (req, ctx) -> {
      seen.set(req);
      return new LlmRawResponse("ok", 1, 1, 2, 0, 0);
    };

    InternalLlmRouter router = InternalLlmRouter.forTesting(CONFIG, Map.of("openai", stub));

    // gpt-4.1-mini는 capabilities에서 supportsThinking=false
    router.execute(LlmRequest.text("hi", "gpt-4.1-mini", 5000), com.returney.flow.domain.llm.LlmCallContext.empty());

    assertThat(seen.get().thinkingBudget()).isEqualTo(0);
  }

  @Test
  void capability_지원_모델은_maxBudget으로_cap() {
    AtomicReference<LlmRequest> seen = new AtomicReference<>();
    LlmExecutor stub = (req, ctx) -> {
      seen.set(req);
      return new LlmRawResponse("ok", 1, 1, 2, 0, 0);
    };

    InternalLlmRouter router = InternalLlmRouter.forTesting(CONFIG, Map.of("anthropic", stub));

    // claude-haiku-4-5: thinkingMaxBudget=10000, 요청 50000 → 10000으로 cap
    router.execute(LlmRequest.text("hi", "claude-haiku-4-5", 50000), com.returney.flow.domain.llm.LlmCallContext.empty());

    assertThat(seen.get().thinkingBudget()).isEqualTo(10000);
  }

  @Test
  void transient_오류는_같은_모델로_retry_소진_후_fallback() {
    AtomicReference<String> fallbackCalled = new AtomicReference<>();
    java.util.concurrent.atomic.AtomicInteger primaryAttempts = new java.util.concurrent.atomic.AtomicInteger(0);

    LlmExecutor flaky = (req, ctx) -> {
      if (req.model().equals("claude-sonnet-4-6")) {
        primaryAttempts.incrementAndGet();
        throw new LlmTransientException("anthropic", 503, "overloaded");
      }
      fallbackCalled.set(req.model());
      return new LlmRawResponse("haiku-ok", 1, 1, 2, 0, 0);
    };

    InternalLlmRouter router = InternalLlmRouter.forTesting(CONFIG, Map.of("anthropic", flaky));

    LlmRawResponse result = router.execute(LlmRequest.text("hi", "claude-sonnet-4-6", 0), com.returney.flow.domain.llm.LlmCallContext.empty());

    // primary는 maxAttempts(3) 만큼 시도, 모두 실패 후 fallback
    assertThat(primaryAttempts.get()).isEqualTo(CONFIG.retryPolicy().maxAttempts());
    assertThat(fallbackCalled.get()).isEqualTo("claude-haiku-4-5");
    assertThat(result.text()).isEqualTo("haiku-ok");
  }

  @Test
  void permanent_오류는_retry도_fallback도_안_함() {
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
    LlmExecutor failing = (req, ctx) -> {
      calls.incrementAndGet();
      throw new LlmClientErrorException("anthropic", 401, "invalid api key");
    };

    InternalLlmRouter router = InternalLlmRouter.forTesting(CONFIG, Map.of("anthropic", failing));

    assertThatThrownBy(() -> router.execute(LlmRequest.text("hi", "claude-sonnet-4-6", 0), com.returney.flow.domain.llm.LlmCallContext.empty()))
        .isInstanceOf(LlmClientErrorException.class)
        .hasMessageContaining("401");

    // 단 1회만 호출 — retry/fallback 모두 미발동
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void transient_retry로_복구되면_fallback_안_가() {
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
    LlmExecutor flaky = (req, ctx) -> {
      int n = calls.incrementAndGet();
      if (n < 3) throw new LlmTransientException("anthropic", 429, "rate limited");
      return new LlmRawResponse("recovered", 1, 1, 2, 0, 0);
    };

    InternalLlmRouter router = InternalLlmRouter.forTesting(CONFIG, Map.of("anthropic", flaky));

    LlmRawResponse result = router.execute(LlmRequest.text("hi", "claude-sonnet-4-6", 0), com.returney.flow.domain.llm.LlmCallContext.empty());

    assertThat(result.text()).isEqualTo("recovered");
    assertThat(calls.get()).isEqualTo(3);   // 3차 시도에서 성공
  }

  @Test
  void fallback_없는_모델은_원래_예외_그대로() {
    LlmExecutor failing = (req, ctx) -> { throw new LlmCallException("permanent", null); };

    // fallback 없는 미니 config (gemini만 routing, fallback 섹션 없음)
    ProvidersConfig configNoFb = ProvidersYamlParser.parse(
        """
        providers:
          gemini:
            type: gemini
            baseUrl: https://example.com
        routing:
          - prefix: "gemini-"
            provider: gemini
        default: gemini-2.5-flash
        """);

    InternalLlmRouter router = InternalLlmRouter.forTesting(configNoFb, Map.of("gemini", failing));

    assertThatThrownBy(() -> router.execute(LlmRequest.text("hi", "gemini-2.5-flash", 0), com.returney.flow.domain.llm.LlmCallContext.empty()))
        .isInstanceOf(LlmCallException.class)
        .hasMessageContaining("permanent");
  }

  @Test
  void maxAttempts_1이면_재시도_없이_즉시_throw_또는_fallback() {
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
    LlmExecutor failing = (req, ctx) -> {
      calls.incrementAndGet();
      throw new LlmTransientException("anthropic", 503, "down");
    };

    // maxAttempts=1, fallback 없는 config
    ProvidersConfig configNoRetryNoFb = ProvidersYamlParser.parse(
        """
        providers:
          anthropic:
            type: anthropic
            baseUrl: https://example.com
        routing:
          - prefix: "claude-"
            provider: anthropic
        default: claude-haiku-4-5
        retry:
          maxAttempts: 1
          initialDelayMs: 0
          maxDelayMs: 0
          backoffMultiplier: 1.0
          jitter: 0.0
        """);

    InternalLlmRouter router = InternalLlmRouter.forTesting(configNoRetryNoFb, Map.of("anthropic", failing));

    assertThatThrownBy(() -> router.execute(LlmRequest.text("hi", "claude-sonnet-4-6", 0), com.returney.flow.domain.llm.LlmCallContext.empty()))
        .isInstanceOf(LlmTransientException.class);

    assertThat(calls.get()).isEqualTo(1);   // 단 1회
  }

  @Test
  void Retry_After가_있으면_exp_backoff보다_길게_대기() {
    java.util.List<Long> sleepDurations = new java.util.ArrayList<>();
    InternalLlmRouter.Sleeper recordingSleeper = sleepDurations::add;

    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
    LlmExecutor flaky = (req, ctx) -> {
      int n = calls.incrementAndGet();
      if (n < 2) {
        // 서버가 30초 대기를 권고
        throw new LlmTransientException("anthropic", 429, "rate", 30_000L);
      }
      return new LlmRawResponse("ok", 1, 1, 2, 0, 0);
    };

    InternalLlmRouter router = InternalLlmRouter.forTesting(
        CONFIG, Map.of("anthropic", flaky), com.returney.flow.port.RateLimiter.unlimited(), recordingSleeper);

    router.execute(LlmRequest.text("hi", "claude-sonnet-4-6", 0), com.returney.flow.domain.llm.LlmCallContext.empty());

    // exp backoff(500ms ±20%)보다 Retry-After(30s)가 더 큼 → 30s 적용
    assertThat(sleepDurations).hasSize(1);
    assertThat(sleepDurations.get(0)).isEqualTo(30_000L);
  }

  @Test
  void Retry_After가_exp_backoff보다_짧으면_exp_backoff_사용() {
    java.util.List<Long> sleepDurations = new java.util.ArrayList<>();
    InternalLlmRouter.Sleeper recordingSleeper = sleepDurations::add;

    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
    LlmExecutor flaky = (req, ctx) -> {
      int n = calls.incrementAndGet();
      if (n < 2) {
        // 서버가 100ms만 권고 (exp backoff 500ms보다 짧음)
        throw new LlmTransientException("anthropic", 429, "rate", 100L);
      }
      return new LlmRawResponse("ok", 1, 1, 2, 0, 0);
    };

    InternalLlmRouter router = InternalLlmRouter.forTesting(
        CONFIG, Map.of("anthropic", flaky), com.returney.flow.port.RateLimiter.unlimited(), recordingSleeper);

    router.execute(LlmRequest.text("hi", "claude-sonnet-4-6", 0), com.returney.flow.domain.llm.LlmCallContext.empty());

    // exp backoff(500ms ±20%)가 더 큼 → exp backoff 사용 (jitter 범위)
    assertThat(sleepDurations).hasSize(1);
    assertThat(sleepDurations.get(0)).isBetween(400L, 600L);
  }

  @Test
  void backoff은_재시도_사이에만_호출됨() {
    java.util.List<Long> sleepDurations = new java.util.ArrayList<>();
    InternalLlmRouter.Sleeper recordingSleeper = sleepDurations::add;

    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger(0);
    LlmExecutor flaky = (req, ctx) -> {
      int n = calls.incrementAndGet();
      if (n < 3) throw new LlmTransientException("anthropic", 429, "rate");
      return new LlmRawResponse("ok", 1, 1, 2, 0, 0);
    };

    InternalLlmRouter router = InternalLlmRouter.forTesting(
        CONFIG, Map.of("anthropic", flaky), com.returney.flow.port.RateLimiter.unlimited(), recordingSleeper);

    router.execute(LlmRequest.text("hi", "claude-sonnet-4-6", 0), com.returney.flow.domain.llm.LlmCallContext.empty());

    // 3회 시도, 사이에 2회 sleep
    assertThat(calls.get()).isEqualTo(3);
    assertThat(sleepDurations).hasSize(2);
    // exp backoff: initial=500ms, mult=2.0 → 500ms, 1000ms (jitter ±20%)
    assertThat(sleepDurations.get(0)).isBetween(400L, 600L);
    assertThat(sleepDurations.get(1)).isBetween(800L, 1200L);
  }

  @Test
  void retry와_fallback이_각각_attemptIndex로_식별됨() {
    LlmExecutor flaky = (req, ctx) -> {
      if (req.model().equals("claude-sonnet-4-6")) {
        throw new LlmTransientException("anthropic", 503, "down");
      }
      return new LlmRawResponse("ok", 1, 1, 2, 0, 0);
    };

    InternalLlmRouter router = InternalLlmRouter.forTesting(CONFIG, Map.of("anthropic", flaky));

    java.util.List<LlmCallEvent> events = new java.util.ArrayList<>();
    ExecutionListener listener = new ExecutionListener() {
      @Override public void onNodeStarted(String n, long t) {}
      @Override public void onNodeCompleted(String n, NodeResult r) {}
      @Override public void onNodeFailed(String n, String e) {}
      @Override public void onNodeSkipped(String n) {}
      @Override public void onFlowCompleted(PipelineResult r) {}
      @Override public void onLlmCall(LlmCallEvent event) { events.add(event); }
    };

    com.returney.flow.domain.llm.LlmCallContext callCtxF =
        new com.returney.flow.domain.llm.LlmCallContext(null, null, Map.of(), listener);
    router.execute(LlmRequest.text("hi", "claude-sonnet-4-6", 0), callCtxF);

    int max = CONFIG.retryPolicy().maxAttempts();
    // primary가 max회 실패 + fallback 1회 성공 = max+1 events
    assertThat(events).hasSize(max + 1);
    // 0..max-1: primary attemptIndex 0..max-1, 모두 실패
    for (int i = 0; i < max; i++) {
      assertThat(events.get(i).attemptIndex()).isEqualTo(i);
      assertThat(events.get(i).success()).isFalse();
      assertThat(events.get(i).resolvedModel()).isEqualTo("claude-sonnet-4-6");
    }
    // max: fallback 첫 시도 (offset = maxAttempts)
    assertThat(events.get(max).attemptIndex()).isEqualTo(max);
    assertThat(events.get(max).success()).isTrue();
    assertThat(events.get(max).resolvedModel()).isEqualTo("claude-haiku-4-5");
  }

  /** 지정된 프로바이더 이름들에 대해서만 가짜 키를 반환. */
  private static ApiKeySupplier fakeKeysFor(String... providerNames) {
    Map<String, String> keys = Map.of();
    keys = new java.util.HashMap<>();
    for (String name : providerNames) keys.put(name, "fake-key-" + name);
    final Map<String, String> finalKeys = Map.copyOf(keys);
    return finalKeys::get;
  }
}
