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

  public static ProvidersConfig parse(String yaml) {
    Map<String, Object> root = new Yaml().load(yaml);
    if (root == null) throw new IllegalArgumentException("providers.yaml is empty");

    Map<String, ProvidersConfig.ProviderEntry> providers = parseProviders(root);
    List<ProvidersConfig.RoutingRule> routing = parseRouting(root, providers.keySet());
    String defaultModel = parseDefaultModel(root);
    ParsedModels models = parseModels(root);
    Map<String, String> fallback = parseFallback(root);

    return new ProvidersConfig(
        providers, routing, defaultModel, models.capabilities, models.rateLimits, fallback);
  }

  // ── section parsers ──

  @SuppressWarnings("unchecked")
  private static Map<String, ProvidersConfig.ProviderEntry> parseProviders(Map<String, Object> root) {
    Map<String, Object> raw = (Map<String, Object>) root.getOrDefault("providers", Map.of());
    Map<String, ProvidersConfig.ProviderEntry> result = new LinkedHashMap<>();
    for (var entry : raw.entrySet()) {
      Map<String, Object> p = (Map<String, Object>) entry.getValue();
      String type = (String) p.get("type");
      String baseUrl = (String) p.get("baseUrl");
      String apiKeyName = (String) p.getOrDefault("apiKeyName", entry.getKey());
      if (type == null || baseUrl == null) {
        throw new IllegalArgumentException(
            "providers." + entry.getKey() + " missing type or baseUrl");
      }
      result.put(entry.getKey(), new ProvidersConfig.ProviderEntry(type, baseUrl, apiKeyName));
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<ProvidersConfig.RoutingRule> parseRouting(
      Map<String, Object> root, java.util.Set<String> knownProviders) {
    List<Map<String, Object>> raw =
        (List<Map<String, Object>>) root.getOrDefault("routing", List.of());
    List<ProvidersConfig.RoutingRule> result = new ArrayList<>();
    for (Map<String, Object> rule : raw) {
      String prefix = (String) rule.get("prefix");
      String provider = (String) rule.get("provider");
      if (prefix == null || provider == null) {
        throw new IllegalArgumentException("routing entry missing prefix or provider");
      }
      if (!knownProviders.contains(provider)) {
        throw new IllegalArgumentException("routing references unknown provider: " + provider);
      }
      result.add(new ProvidersConfig.RoutingRule(prefix, provider));
    }
    return result;
  }

  private static String parseDefaultModel(Map<String, Object> root) {
    String defaultModel = (String) root.get("default");
    if (defaultModel == null || defaultModel.isBlank()) {
      throw new IllegalArgumentException("providers.yaml missing 'default' model");
    }
    return defaultModel;
  }

  @SuppressWarnings("unchecked")
  private static ParsedModels parseModels(Map<String, Object> root) {
    Map<String, Object> raw = (Map<String, Object>) root.getOrDefault("models", Map.of());
    Map<String, ProvidersConfig.ModelCapability> capabilities = new LinkedHashMap<>();
    Map<String, ProvidersConfig.ModelLimits> rateLimits = new LinkedHashMap<>();
    for (var entry : raw.entrySet()) {
      String name = entry.getKey();
      Map<String, Object> attrs = (Map<String, Object>) entry.getValue();
      capabilities.put(name, parseCapability(attrs));
      ProvidersConfig.ModelLimits limits = parseRateLimit(name, attrs);
      if (limits != null) rateLimits.put(name, limits);
    }
    return new ParsedModels(capabilities, rateLimits);
  }

  private static ProvidersConfig.ModelCapability parseCapability(Map<String, Object> attrs) {
    boolean supportsThinking = Boolean.TRUE.equals(attrs.get("supportsThinking"));
    int maxBudget = attrs.get("thinkingMaxBudget") instanceof Number n ? n.intValue() : 0;
    return new ProvidersConfig.ModelCapability(supportsThinking, maxBudget);
  }

  @SuppressWarnings("unchecked")
  private static ProvidersConfig.ModelLimits parseRateLimit(
      String modelName, Map<String, Object> attrs) {
    Object rate = attrs.get("rate");
    if (!(rate instanceof Map)) return null;
    Map<String, Object> r = (Map<String, Object>) rate;
    int rpm = r.get("rpm") instanceof Number n ? n.intValue() : 0;
    long tpm = r.get("tpm") instanceof Number n ? n.longValue() : 0L;
    if (rpm <= 0 || tpm <= 0) {
      throw new IllegalArgumentException(
          "models." + modelName + ".rate must have positive 'rpm' and 'tpm'");
    }
    return new ProvidersConfig.ModelLimits(rpm, tpm);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> parseFallback(Map<String, Object> root) {
    Map<String, Object> raw = (Map<String, Object>) root.getOrDefault("fallback", Map.of());
    Map<String, String> result = new LinkedHashMap<>();
    for (var entry : raw.entrySet()) {
      if (entry.getValue() instanceof String fb) result.put(entry.getKey(), fb);
    }
    return result;
  }

  /** parseModels의 두 결과를 한 번에 반환하기 위한 내부 record. */
  private record ParsedModels(
      Map<String, ProvidersConfig.ModelCapability> capabilities,
      Map<String, ProvidersConfig.ModelLimits> rateLimits) {}
}
