package com.returney.flow.adapter.parser;

import java.util.List;
import java.util.Map;

/**
 * providers.yaml 파싱 결과.
 *
 * @param providers 프로바이더 정의 (key = providerName)
 * @param routing 모델명 prefix → providerName 매핑 (첫 매치 승리)
 * @param defaultModel 호출 시 model이 비어있을 때 사용할 기본 모델명
 * @param capabilities 모델별 capability (선택). 없으면 모든 모델이 supportsThinking=false
 * @param fallbackChain 모델별 1단 fallback 매핑. exact → prefix → "default" 순으로 조회
 */
public record ProvidersConfig(
    Map<String, ProviderEntry> providers,
    List<RoutingRule> routing,
    String defaultModel,
    Map<String, ModelCapability> capabilities,
    Map<String, String> fallbackChain) {

  /**
   * 프로바이더 정의.
   *
   * @param type 프로바이더 종류 (gemini, anthropic, openai, openai-reasoning)
   * @param baseUrl API base URL
   * @param apiKeyName API 키 조회 시 ApiKeySupplier에 전달할 이름
   */
  public record ProviderEntry(String type, String baseUrl, String apiKeyName) {}

  /** 모델명 prefix → providerName 매핑 한 줄. */
  public record RoutingRule(String prefix, String provider) {}

  /**
   * 모델 capability.
   *
   * @param supportsThinking thinking 토큰 예산 지정 가능 여부
   * @param thinkingMaxBudget thinking 토큰 최대치 (지원 안 하면 무시)
   */
  public record ModelCapability(boolean supportsThinking, int thinkingMaxBudget) {
    public static final ModelCapability NONE = new ModelCapability(false, 0);
  }

  /** model 명에 매칭되는 providerName 반환. 매치 없으면 null. */
  public String resolveProvider(String model) {
    if (model == null || model.isBlank()) return null;
    for (RoutingRule rule : routing) {
      if (model.startsWith(rule.prefix())) return rule.provider();
    }
    return null;
  }

  /** 모델 capability 반환. 미등록이면 NONE (supportsThinking=false). */
  public ModelCapability capability(String model) {
    if (model == null) return ModelCapability.NONE;
    return capabilities.getOrDefault(model, ModelCapability.NONE);
  }

  /**
   * 모델의 fallback 모델명 반환. exact key → prefix 매칭 → "default" 키 → null.
   * fallback이 자기 자신을 가리키면 (loop 방지) null 반환.
   */
  public String fallbackFor(String model) {
    if (model == null) return null;
    String exact = fallbackChain.get(model);
    if (exact != null && !exact.equals(model)) return exact;
    for (var entry : fallbackChain.entrySet()) {
      if ("default".equals(entry.getKey())) continue;
      if (model.startsWith(entry.getKey()) && !entry.getValue().equals(model)) {
        return entry.getValue();
      }
    }
    String def = fallbackChain.get("default");
    return (def != null && !def.equals(model)) ? def : null;
  }
}
