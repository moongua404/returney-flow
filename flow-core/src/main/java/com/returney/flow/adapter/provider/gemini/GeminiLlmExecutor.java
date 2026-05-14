package com.returney.flow.adapter.provider.gemini;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.returney.flow.adapter.common.HttpUtil;
import com.returney.flow.port.LlmExecutor;
import com.returney.flow.domain.llm.LlmCallContext;
import com.returney.flow.domain.llm.LlmRawResponse;
import com.returney.flow.domain.llm.LlmRequest;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Gemini API LlmExecutor 구현체. Flash / Pro / Lite 모든 Gemini 모델을 처리한다.
 *
 * <p>요청은 항상 {@code system_instruction(optional) + contents=[user prompt (+optional binary)]}
 * 단일 형식. multi-turn 대화 상태는 호출자가 prompt 텍스트에 미리 인터폴레이션해야 한다.
 */
public class GeminiLlmExecutor implements LlmExecutor {

  private static final String PROVIDER = "Gemini";
  private static final Gson GSON = new Gson();

  private final GeminiConfig config;
  private final HttpClient httpClient;
  private final int requestTimeoutSec;
  private final Map<String, String> headers;

  public GeminiLlmExecutor(GeminiConfig config) {
    this(config, 10, 300);
  }

  public GeminiLlmExecutor(GeminiConfig config, int connectTimeoutSec, int requestTimeoutSec) {
    this.config = config;
    this.httpClient = HttpUtil.newClient(connectTimeoutSec);
    this.requestTimeoutSec = requestTimeoutSec;
    this.headers = Map.of("X-Goog-Api-Key", config.apiKey());
  }

  @Override
  public LlmRawResponse execute(LlmRequest request, LlmCallContext ctx) {
    String effectiveModel = (request.model() != null && !request.model().isEmpty())
        ? request.model() : config.defaultModel();
    String url = config.baseUrl() + "/" + effectiveModel + ":generateContent";

    String body = buildBody(request);
    String responseBody = HttpUtil.postJsonOrThrow(
        httpClient, url, body, headers, PROVIDER, requestTimeoutSec);
    Resp resp = parseResp(responseBody);
    String text = extractText(resp);

    UsageMetadata usage = resp != null ? resp.usageMetadata() : null;
    int inputTokens = usage != null
        ? usage.promptTokenCount()
        : estimateTokens(combineForEstimate(request.systemPrompt(), request.prompt()));
    int candidateTokens = usage != null ? usage.candidatesTokenCount() : estimateTokens(text);
    int thinkingTokens = usage != null ? usage.thoughtsTokenCount() : 0;
    int outputTokens = Math.max(0, candidateTokens - thinkingTokens);
    return new LlmRawResponse(text, inputTokens, outputTokens, thinkingTokens, 0, 0);
  }

  private String buildBody(LlmRequest request) {
    int budget = request.thinkingBudget();
    ThinkingConfig thinkingConfig = budget > 0 ? new ThinkingConfig(budget) : null;
    // 멀티모달 요청은 결정성을 위해 temperature 0.1 (기존 동작 유지).
    double temperature = request.isMultimodal() ? 0.1 : config.temperature();
    // 모든 호출은 strict JSON 응답 강제. 현 시점 22개 prompt 전부 JSON 출력 기대 — 평문
    // reasoning을 그대로 답하던 모델 변동성을 차단한다.
    GenerationConfig genConfig =
        new GenerationConfig(
            temperature, config.maxOutputTokens(), thinkingConfig, "application/json");

    List<Part> parts = new ArrayList<>(2);
    parts.add(new Part(request.prompt(), null));
    if (request.isMultimodal()) {
      String base64 = Base64.getEncoder().encodeToString(request.binaryContent());
      parts.add(new Part(null, new InlineData(request.mimeType(), base64)));
    }
    Content userContent = new Content("user", parts);

    SystemInstruction sys =
        (request.systemPrompt() != null && !request.systemPrompt().isBlank())
            ? new SystemInstruction(List.of(new Part(request.systemPrompt(), null)))
            : null;
    return GSON.toJson(new Req(sys, List.of(userContent), genConfig));
  }

  private static String combineForEstimate(String system, String prompt) {
    int sysLen = system != null ? system.length() : 0;
    int pLen = prompt != null ? prompt.length() : 0;
    StringBuilder sb = new StringBuilder(sysLen + pLen);
    if (system != null) sb.append(system);
    if (prompt != null) sb.append(prompt);
    return sb.toString();
  }

  private Resp parseResp(String json) {
    try {
      return GSON.fromJson(json, Resp.class);
    } catch (Exception e) {
      return null;
    }
  }

  private String extractText(Resp resp) {
    if (resp == null || resp.candidates() == null || resp.candidates().isEmpty()) return "";
    ContentBody content = resp.candidates().get(0).content();
    if (content == null || content.parts() == null || content.parts().isEmpty()) return "";
    String lastText = "";
    for (Part p : content.parts()) {
      if (p.text() != null) lastText = p.text();
    }
    return lastText;
  }

  // 폴백 추정 (usageMetadata 부재 시만 사용). InternalLlmRouter의 input 추정(length/4)과
  // 동일한 휴리스틱은 아니지만, 실측 토큰이 우선이므로 차이 영향이 작음.
  private int estimateTokens(String text) {
    return text == null ? 0 : Math.max(1, text.length() / 3);
  }

  // ── Request DTOs ──────────────────────────────────────────────────────────

  private record Req(
      @SerializedName("system_instruction") SystemInstruction systemInstruction,
      List<Content> contents,
      GenerationConfig generationConfig) {}

  private record SystemInstruction(List<Part> parts) {}

  private record Content(String role, List<Part> parts) {}

  private record Part(String text, @SerializedName("inline_data") InlineData inlineData) {}

  private record InlineData(@SerializedName("mime_type") String mimeType, String data) {}

  private record GenerationConfig(
      double temperature,
      int maxOutputTokens,
      ThinkingConfig thinkingConfig,
      @SerializedName("response_mime_type") String responseMimeType) {}

  private record ThinkingConfig(int thinkingBudget) {}

  // ── Response DTOs ─────────────────────────────────────────────────────────

  private record Resp(
      List<Candidate> candidates,
      @SerializedName("usageMetadata") UsageMetadata usageMetadata) {}

  private record Candidate(ContentBody content) {}

  private record ContentBody(List<Part> parts) {}

  private record UsageMetadata(
      @SerializedName("promptTokenCount") int promptTokenCount,
      @SerializedName("candidatesTokenCount") int candidatesTokenCount,
      @SerializedName("thoughtsTokenCount") int thoughtsTokenCount) {}
}
