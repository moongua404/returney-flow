package com.returney.flow.application;

import com.returney.flow.adapter.parser.ProvidersConfig;
import com.returney.flow.adapter.parser.ProvidersYamlParser;
import com.returney.flow.adapter.provider.anthropic.ClaudeLlmExecutor;
import com.returney.flow.adapter.provider.gemini.GeminiConfig;
import com.returney.flow.adapter.provider.gemini.GeminiLlmExecutor;
import com.returney.flow.adapter.provider.openai.GptLlmExecutor;
import com.returney.flow.adapter.provider.openai.ReasoningLlmExecutor;
import com.returney.flow.domain.llm.LlmCallEvent;
import com.returney.flow.domain.llm.LlmCallException;
import com.returney.flow.domain.llm.LlmRawResponse;
import com.returney.flow.domain.llm.LlmRequest;
import com.returney.flow.port.ApiKeySupplier;
import com.returney.flow.port.ExecutionListener;
import com.returney.flow.port.LlmExecutor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 모델명을 기반으로 LLM 프로바이더를 라우팅하는 라우터.
 *
 * <p>flow-core 내부 구현. 코드젠이 만든 *PipelineBase가 자동 사용.
 *
 * <p>호출 1회마다 {@link ExecutionListener#onLlmCall} 이벤트를 발행한다 (성공/실패 모두).
 * failover는 Phase 4b-2에서 추가 예정.
 */
public final class InternalLlmRouter implements LlmExecutor {

  private final ProvidersConfig config;
  private final Map<String, LlmExecutor> executors;

  // 컨텍스트는 호출 스레드별로 보관. virtual thread fan-out에선 호출 직전 setX가 매번 일어남.
  private final ThreadLocal<UUID> currentSessionId = new ThreadLocal<>();
  private final ThreadLocal<String> currentAction = new ThreadLocal<>();
  private final ThreadLocal<ExecutionListener> currentListener =
      ThreadLocal.withInitial(ExecutionListener::noop);

  private InternalLlmRouter(ProvidersConfig config, Map<String, LlmExecutor> executors) {
    this.config = config;
    this.executors = executors;
  }

  /** 테스트 전용 — 명시적 executor 맵으로 라우터 생성. */
  static InternalLlmRouter forTesting(ProvidersConfig config, Map<String, LlmExecutor> executors) {
    return new InternalLlmRouter(config, new HashMap<>(executors));
  }

  /** 클래스패스 {@code providers.yaml} + 환경변수 키로 라우터 생성. */
  public static InternalLlmRouter fromClasspath() {
    return from(ProvidersYamlParser.loadFromClasspath(), ApiKeySupplier.fromEnv());
  }

  /** 명시적 키 공급자로 라우터 생성. */
  public static InternalLlmRouter from(ProvidersConfig config, ApiKeySupplier keys) {
    Map<String, LlmExecutor> executors = new HashMap<>();
    for (var entry : config.providers().entrySet()) {
      String providerName = entry.getKey();
      ProvidersConfig.ProviderEntry p = entry.getValue();
      String key = keys.get(p.apiKeyName());
      if (key == null || key.isBlank()) {
        // 키 없는 프로바이더는 등록하지 않음 — 해당 모델 호출 시 명확한 에러 발생
        continue;
      }
      LlmExecutor exec = buildExecutor(p, key);
      if (exec != null) executors.put(providerName, exec);
    }
    return new InternalLlmRouter(config, executors);
  }

  private static LlmExecutor buildExecutor(ProvidersConfig.ProviderEntry p, String apiKey) {
    return switch (p.type()) {
      case "gemini" -> new GeminiLlmExecutor(GeminiConfig.of(apiKey));
      case "anthropic" -> new ClaudeLlmExecutor(apiKey, p.baseUrl());
      case "openai" -> new GptLlmExecutor(apiKey, p.baseUrl());
      case "openai-reasoning" -> new ReasoningLlmExecutor(apiKey, p.baseUrl());
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

    String sessionId = currentSessionId.get() != null ? currentSessionId.get().toString() : null;
    String action = currentAction.get();
    ExecutionListener listener = currentListener.get();

    LlmRequest first = applyCapability(ensureModel(request, model), model);
    try {
      return attempt(first, model, sessionId, action, listener, 0);
    } catch (RuntimeException primaryError) {
      String fallbackModel = config.fallbackFor(model);
      if (fallbackModel == null || !isFallbackEligible(primaryError)) {
        throw primaryError;
      }
      LlmRequest fbRequest = applyCapability(ensureModel(request, fallbackModel), fallbackModel);
      return attempt(fbRequest, fallbackModel, sessionId, action, listener, 1);
    }
  }

  private LlmRawResponse attempt(
      LlmRequest request, String model, String sessionId, String action,
      ExecutionListener listener, int attemptIndex) throws LlmCallException {
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

    long start = System.currentTimeMillis();
    try {
      LlmRawResponse response = exec.execute(request);
      long latency = System.currentTimeMillis() - start;
      safelyEmit(listener,
          LlmCallEvent.success(sessionId, action, request, response, model, latency, attemptIndex));
      return response;
    } catch (RuntimeException e) {
      long latency = System.currentTimeMillis() - start;
      safelyEmit(listener,
          LlmCallEvent.failure(sessionId, action, request, model, latency, e, attemptIndex));
      throw e;
    }
  }

  /**
   * fallback 트리거 여부. 4xx/auth 같은 항구적 오류는 굳이 fallback 안 함.
   * 보수적으로: LlmCallException 자체의 cause나 메시지로 transient 판단. 지금은 단순화 —
   * 모든 RuntimeException이 fallback 대상. Phase 4b-3 또는 백엔드 sweep에서 정교화.
   */
  private static boolean isFallbackEligible(Throwable error) {
    return true;
  }

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
