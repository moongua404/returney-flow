package com.returney.flow.application;

import com.returney.flow.adapter.parser.ProvidersConfig;
import com.returney.flow.adapter.parser.ProvidersYamlParser;
import com.returney.flow.adapter.provider.anthropic.ClaudeLlmExecutor;
import com.returney.flow.adapter.provider.gemini.GeminiConfig;
import com.returney.flow.adapter.provider.gemini.GeminiLlmExecutor;
import com.returney.flow.adapter.provider.openai.GptLlmExecutor;
import com.returney.flow.adapter.provider.openai.ReasoningLlmExecutor;
import com.returney.flow.domain.llm.LlmCallException;
import com.returney.flow.domain.llm.LlmRawResponse;
import com.returney.flow.domain.llm.LlmRequest;
import com.returney.flow.port.ApiKeySupplier;
import com.returney.flow.port.LlmExecutor;
import java.util.HashMap;
import java.util.Map;

/**
 * 모델명을 기반으로 LLM 프로바이더를 라우팅하는 라우터.
 *
 * <p>flow-core 내부 구현. 코드젠이 만든 *PipelineBase가 자동 사용.
 *
 * <p>지금은 단순 라우팅(prefix → provider)만 지원. failover/lifecycle 훅은 Phase 4b.
 */
public final class InternalLlmRouter implements LlmExecutor {

  private final ProvidersConfig config;
  private final Map<String, LlmExecutor> executors;

  private InternalLlmRouter(ProvidersConfig config, Map<String, LlmExecutor> executors) {
    this.config = config;
    this.executors = executors;
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
  public LlmRawResponse execute(LlmRequest request) throws LlmCallException {
    String model = request.model();
    if (model == null || model.isBlank()) {
      model = config.defaultModel();
    }
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
    return exec.execute(ensureModel(request, model));
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
