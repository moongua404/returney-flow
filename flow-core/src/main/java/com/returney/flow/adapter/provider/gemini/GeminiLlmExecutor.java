package com.returney.flow.adapter.provider.gemini;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.returney.flow.adapter.common.HttpUtil;
import com.returney.flow.port.LlmExecutor;
import com.returney.flow.domain.llm.LlmRawResponse;
import com.returney.flow.domain.llm.LlmRequest;
import java.net.http.HttpClient;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Gemini API LlmExecutor 구현체.
 *
 * <p>Flash / Pro / Lite 모든 Gemini 모델을 처리한다.
 * Spring 의존 없이 {@code java.net.http.HttpClient}와 Gson만 사용한다.
 */
public class GeminiLlmExecutor implements LlmExecutor {

  private static final String PROVIDER = "Gemini";
  private static final Gson GSON = new Gson();
  private static final Map<String, String> HEADERS = Map.of();

  private final GeminiConfig config;
  private final HttpClient httpClient;
  private final int requestTimeoutSec;

  public GeminiLlmExecutor(GeminiConfig config) {
    this(config, 10, 300);
  }

  public GeminiLlmExecutor(GeminiConfig config, int connectTimeoutSec, int requestTimeoutSec) {
    this.config = config;
    this.httpClient = HttpUtil.newClient(connectTimeoutSec);
    this.requestTimeoutSec = requestTimeoutSec;
  }

  @Override
  public LlmRawResponse execute(LlmRequest request) {
    String renderedPrompt = request.singlePrompt();
    String effectiveModel = (request.model() != null && !request.model().isEmpty())
        ? request.model() : config.defaultModel();
    int estimatedTokens = estimateTokens(renderedPrompt);
    String url = config.baseUrl() + "/" + effectiveModel + ":generateContent?key=" + config.apiKey();
    String body = request.isMultimodal()
        ? buildMultimodalBody(renderedPrompt, request.binaryContent(), request.mimeType())
        : buildRequestBody(renderedPrompt, request.thinkingBudget());
    String responseBody = HttpUtil.postJsonOrThrow(
        httpClient, url, body, HEADERS, PROVIDER, requestTimeoutSec);
    String text = extractText(responseBody);
    return new LlmRawResponse(text, estimatedTokens, estimateTokens(text), 0, 0, 0);
  }

  private String buildRequestBody(String prompt, int thinkingBudget) {
    ThinkingConfig thinkingConfig = thinkingBudget > 0 ? new ThinkingConfig(thinkingBudget) : null;
    GenerationConfig genConfig = new GenerationConfig(config.temperature(), config.maxOutputTokens(), thinkingConfig);
    return GSON.toJson(new Req(List.of(new Content(List.of(new Part(prompt, null)))), genConfig));
  }

  private String buildMultimodalBody(String textPrompt, byte[] binary, String mimeType) {
    String base64 = Base64.getEncoder().encodeToString(binary);
    GenerationConfig genConfig = new GenerationConfig(0.1, config.maxOutputTokens(), null);
    List<Part> parts = List.of(
        new Part(textPrompt, null),
        new Part(null, new InlineData(mimeType, base64)));
    return GSON.toJson(new Req(List.of(new Content(parts)), genConfig));
  }

  private String extractText(String json) {
    try {
      Resp resp = GSON.fromJson(json, Resp.class);
      if (resp.candidates() == null || resp.candidates().isEmpty()) return "";
      ContentBody content = resp.candidates().get(0).content();
      if (content == null || content.parts() == null || content.parts().isEmpty()) return "";
      String lastText = "";
      for (Part p : content.parts()) {
        if (p.text() != null) lastText = p.text();
      }
      return lastText;
    } catch (Exception e) {
      return "";
    }
  }

  private int estimateTokens(String text) {
    return text == null ? 0 : Math.max(1, text.length() / 3);
  }

  // ── Request DTOs ──────────────────────────────────────────────────────────

  private record Req(List<Content> contents, GenerationConfig generationConfig) {}

  private record Content(List<Part> parts) {}

  private record Part(String text, @SerializedName("inline_data") InlineData inlineData) {}

  private record InlineData(@SerializedName("mime_type") String mimeType, String data) {}

  private record GenerationConfig(double temperature, int maxOutputTokens, ThinkingConfig thinkingConfig) {}

  private record ThinkingConfig(int thinkingBudget) {}

  // ── Response DTOs ─────────────────────────────────────────────────────────

  private record Resp(List<Candidate> candidates) {}

  private record Candidate(ContentBody content) {}

  private record ContentBody(List<Part> parts) {}

}
