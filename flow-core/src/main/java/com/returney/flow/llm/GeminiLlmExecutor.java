package com.returney.flow.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.returney.flow.spi.LlmExecutor;
import com.returney.flow.spi.LlmLogger;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Gemini API를 직접 호출하는 LlmExecutor 구현체.
 *
 * <p>Spring 의존 없이 {@code java.net.http.HttpClient}와 Gson만 사용한다.
 * flow-core 라이브러리의 기본 LLM 구현으로, 호출자가 별도 구현을 제공하지 않을 때 사용.
 */
public class GeminiLlmExecutor implements LlmExecutor {

  private final LlmConfig config;
  private final HttpClient httpClient;
  private final LlmLogger logger;

  public GeminiLlmExecutor(LlmConfig config) {
    this(config, null);
  }

  public GeminiLlmExecutor(LlmConfig config, LlmLogger logger) {
    this.config = config;
    this.logger = logger;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  @Override
  public String execute(String renderedPrompt, String model, int thinkingBudget) {
    LlmResponse response = executeWithMeta(renderedPrompt, model, thinkingBudget);
    return response.text();
  }

  /**
   * LLM을 호출하고 메타데이터를 포함한 응답을 반환한다.
   */
  public LlmResponse executeWithMeta(String renderedPrompt, String model, int thinkingBudget) {
    String effectiveModel = (model != null && !model.isEmpty()) ? model : config.defaultModel();
    long startMs = System.currentTimeMillis();

    try {
      String requestBody = buildRequestBody(renderedPrompt, thinkingBudget);
      String url = config.baseUrl() + "/" + effectiveModel + ":generateContent?key=" + config.apiKey();

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Content-Type", "application/json")
          .timeout(Duration.ofMinutes(5))
          .POST(HttpRequest.BodyPublishers.ofString(requestBody))
          .build();

      HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      long latencyMs = System.currentTimeMillis() - startMs;

      if (httpResponse.statusCode() != 200) {
        String error = "Gemini API error " + httpResponse.statusCode() + ": " + extractError(httpResponse.body());
        if (logger != null) {
          logger.log("llm-call", effectiveModel, estimateTokens(renderedPrompt), 0, latencyMs, false, error);
        }
        throw new RuntimeException(error);
      }

      String text = extractText(httpResponse.body());
      int inputTokens = estimateTokens(renderedPrompt);
      int outputTokens = estimateTokens(text);

      if (logger != null) {
        logger.log("llm-call", effectiveModel, inputTokens, outputTokens, latencyMs, true, null);
      }

      return new LlmResponse(text, effectiveModel, latencyMs, inputTokens, outputTokens);
    } catch (IOException | InterruptedException e) {
      long latencyMs = System.currentTimeMillis() - startMs;
      String error = e.getMessage();
      if (logger != null) {
        logger.log("llm-call", effectiveModel, estimateTokens(renderedPrompt), 0, latencyMs, false, error);
      }
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new RuntimeException("LLM 호출 실패: " + error, e);
    }
  }

  private String buildRequestBody(String prompt, int thinkingBudget) {
    JsonObject contents = new JsonObject();
    JsonArray partsArray = new JsonArray();
    JsonObject textPart = new JsonObject();
    textPart.addProperty("text", prompt);
    partsArray.add(textPart);

    JsonObject contentObj = new JsonObject();
    contentObj.add("parts", partsArray);

    JsonArray contentsArray = new JsonArray();
    contentsArray.add(contentObj);

    JsonObject body = new JsonObject();
    body.add("contents", contentsArray);

    JsonObject genConfig = new JsonObject();
    genConfig.addProperty("temperature", config.temperature());
    genConfig.addProperty("maxOutputTokens", config.maxOutputTokens());
    if (thinkingBudget > 0) {
      JsonObject thinkingConfig = new JsonObject();
      thinkingConfig.addProperty("thinkingBudget", thinkingBudget);
      genConfig.add("thinkingConfig", thinkingConfig);
    }
    body.add("generationConfig", genConfig);

    return body.toString();
  }

  /**
   * Gemini API 응답에서 마지막 text 파트를 추출한다.
   * thinking 모드에서는 thought 파트가 먼저 오고 text 파트가 마지막에 오므로,
   * 마지막 text를 가져온다.
   */
  private String extractText(String json) {
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      JsonArray candidates = root.getAsJsonArray("candidates");
      if (candidates == null || candidates.isEmpty()) return "";

      JsonObject firstCandidate = candidates.get(0).getAsJsonObject();
      JsonObject content = firstCandidate.getAsJsonObject("content");
      if (content == null) return "";

      JsonArray parts = content.getAsJsonArray("parts");
      if (parts == null || parts.isEmpty()) return "";

      // 마지막 text 파트 추출 (thinking 파트 스킵)
      String lastText = "";
      for (JsonElement part : parts) {
        JsonObject partObj = part.getAsJsonObject();
        if (partObj.has("text")) {
          lastText = partObj.get("text").getAsString();
        }
      }
      return lastText;
    } catch (Exception e) {
      return "";
    }
  }

  private String extractError(String json) {
    try {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      if (root.has("error")) {
        JsonObject error = root.getAsJsonObject("error");
        if (error.has("message")) {
          return error.get("message").getAsString();
        }
      }
    } catch (Exception ignored) {
    }
    return json.length() > 200 ? json.substring(0, 200) : json;
  }

  private int estimateTokens(String text) {
    return text == null ? 0 : Math.max(1, text.length() / 3);
  }
}
