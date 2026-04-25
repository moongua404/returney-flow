package com.returney.flow.adapter.parser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** providers.yaml 로더. */
public final class ProvidersYamlParser {

  private static final String DEFAULT_RESOURCE = "providers.yaml";

  private ProvidersYamlParser() {}

  /** 클래스패스 {@code providers.yaml} 로드. 없으면 IllegalStateException. */
  public static ProvidersConfig loadFromClasspath() {
    return loadFromClasspath(DEFAULT_RESOURCE);
  }

  public static ProvidersConfig loadFromClasspath(String resourcePath) {
    InputStream is =
        ProvidersYamlParser.class.getClassLoader().getResourceAsStream(resourcePath);
    if (is == null) {
      throw new IllegalStateException("providers.yaml not found on classpath: " + resourcePath);
    }
    try (is) {
      return parse(new String(is.readAllBytes(), StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load " + resourcePath, e);
    }
  }

  @SuppressWarnings("unchecked")
  public static ProvidersConfig parse(String yaml) {
    Map<String, Object> root = new Yaml().load(yaml);
    if (root == null) {
      throw new IllegalArgumentException("providers.yaml is empty");
    }

    Map<String, Object> rawProviders =
        (Map<String, Object>) root.getOrDefault("providers", Map.of());
    Map<String, ProvidersConfig.ProviderEntry> providers = new LinkedHashMap<>();
    for (var entry : rawProviders.entrySet()) {
      Map<String, Object> p = (Map<String, Object>) entry.getValue();
      String type = (String) p.get("type");
      String baseUrl = (String) p.get("baseUrl");
      String apiKeyName = (String) p.getOrDefault("apiKeyName", entry.getKey());
      if (type == null || baseUrl == null) {
        throw new IllegalArgumentException(
            "providers." + entry.getKey() + " missing type or baseUrl");
      }
      providers.put(entry.getKey(), new ProvidersConfig.ProviderEntry(type, baseUrl, apiKeyName));
    }

    List<Map<String, Object>> rawRouting =
        (List<Map<String, Object>>) root.getOrDefault("routing", List.of());
    List<ProvidersConfig.RoutingRule> routing = new ArrayList<>();
    for (Map<String, Object> rule : rawRouting) {
      String prefix = (String) rule.get("prefix");
      String provider = (String) rule.get("provider");
      if (prefix == null || provider == null) {
        throw new IllegalArgumentException("routing entry missing prefix or provider");
      }
      if (!providers.containsKey(provider)) {
        throw new IllegalArgumentException(
            "routing references unknown provider: " + provider);
      }
      routing.add(new ProvidersConfig.RoutingRule(prefix, provider));
    }

    String defaultModel = (String) root.get("default");
    if (defaultModel == null || defaultModel.isBlank()) {
      throw new IllegalArgumentException("providers.yaml missing 'default' model");
    }

    Map<String, ProvidersConfig.ModelCapability> capabilities = new LinkedHashMap<>();
    Map<String, Object> rawCaps = (Map<String, Object>) root.getOrDefault("capabilities", Map.of());
    for (var entry : rawCaps.entrySet()) {
      Map<String, Object> c = (Map<String, Object>) entry.getValue();
      boolean supportsThinking = Boolean.TRUE.equals(c.get("supportsThinking"));
      int maxBudget =
          c.get("thinkingMaxBudget") instanceof Number n ? n.intValue() : 0;
      capabilities.put(
          entry.getKey(), new ProvidersConfig.ModelCapability(supportsThinking, maxBudget));
    }

    Map<String, String> fallbackChain = new LinkedHashMap<>();
    Map<String, Object> rawFb = (Map<String, Object>) root.getOrDefault("fallback", Map.of());
    for (var entry : rawFb.entrySet()) {
      if (entry.getValue() instanceof String fb) fallbackChain.put(entry.getKey(), fb);
    }

    return new ProvidersConfig(providers, routing, defaultModel, capabilities, fallbackChain);
  }
}
