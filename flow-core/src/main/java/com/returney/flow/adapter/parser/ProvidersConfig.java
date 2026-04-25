package com.returney.flow.adapter.parser;

import java.util.List;
import java.util.Map;

/**
 * providers.yaml 파싱 결과.
 *
 * @param providers 프로바이더 정의 (key = providerName)
 * @param routing 모델명 prefix → providerName 매핑 (첫 매치 승리)
 * @param defaultModel 호출 시 model이 비어있을 때 사용할 기본 모델명
 */
public record ProvidersConfig(
    Map<String, ProviderEntry> providers,
    List<RoutingRule> routing,
    String defaultModel) {

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

  /** model 명에 매칭되는 providerName 반환. 매치 없으면 null. */
  public String resolveProvider(String model) {
    if (model == null || model.isBlank()) return null;
    for (RoutingRule rule : routing) {
      if (model.startsWith(rule.prefix())) return rule.provider();
    }
    return null;
  }
}
