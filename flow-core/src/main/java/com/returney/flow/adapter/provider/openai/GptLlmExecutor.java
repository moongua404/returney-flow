package com.returney.flow.adapter.provider.openai;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.returney.flow.adapter.common.HttpUtil;
import com.returney.flow.port.LlmExecutor;
import com.returney.flow.domain.llm.LlmCallContext;
import com.returney.flow.domain.llm.LlmRawResponse;
import com.returney.flow.domain.llm.LlmRequest;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

/**
 * OpenAI GPT API LlmExecutor 구현체.
 *
 * <p>gpt-4o, gpt-4o-mini 등 GPT 계열 모델을 처리한다.
 * Structured Outputs(json_schema)를 지원한다.
 * o1/o3 등 reasoning 모델은 {@link ReasoningLlmExecutor}를 사용한다.
 */
public class GptLlmExecutor implements LlmExecutor {

  private static final String PROVIDER = "GPT";
  private static final Gson GSON = new Gson();
  private static final int MAX_TOKENS = 16384;

  private final String apiKey;
  private final String baseUrl;
  private final HttpClient httpClient;
  private final int requestTimeoutSec;

  public GptLlmExecutor(String apiKey) {
    this(apiKey, "https://api.openai.com", 10, 300);
  }

  public GptLlmExecutor(String apiKey, String baseUrl) {
    this(apiKey, baseUrl, 10, 300);
  }

  public GptLlmExecutor(String apiKey, String baseUrl, int connectTimeoutSec, int requestTimeoutSec) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
    this.httpClient = HttpUtil.newClient(connectTimeoutSec);
    this.requestTimeoutSec = requestTimeoutSec;
  }

  @Override
  public LlmRawResponse execute(LlmRequest request, LlmCallContext ctx) {
    return callApi(buildBody(request), request.model());
  }

  private LlmRawResponse callApi(String body, String model) {
    Map<String, String> headers = Map.of("Authorization", "Bearer " + apiKey);
    String responseBody = HttpUtil.postJsonOrThrow(
        httpClient, baseUrl + "/v1/chat/completions", body, headers, PROVIDER, requestTimeoutSec);
    return parseResponse(responseBody);
  }

  /**
   * system + user 메시지로 단일 호출. response_format: json_object 강제.
   */
  private String buildBody(LlmRequest request) {
    List<Msg> messages = new java.util.ArrayList<>(2);
    if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
      messages.add(new Msg("system", request.systemPrompt()));
    }
    messages.add(new Msg("user", request.prompt()));
    return GSON.toJson(new Req(
        request.model(), MAX_TOKENS, messages, new ResponseFormat("json_object", null)));
  }

  private LlmRawResponse parseResponse(String rawResponse) {
    Resp resp = GSON.fromJson(rawResponse, Resp.class);
    String text = (resp.choices() != null && !resp.choices().isEmpty())
        ? HttpUtil.stripCodeBlock(resp.choices().get(0).message().content())
        : "";
    Usage u = resp.usage() != null ? resp.usage() : new Usage(0, 0);
    return new LlmRawResponse(text, u.promptTokens(), u.completionTokens(), 0, 0, 0);
  }

  // ── Request DTOs ──────────────────────────────────────────────────────────

  private record Req(
      String model,
      @SerializedName("max_tokens") int maxTokens,
      List<Msg> messages,
      @SerializedName("response_format") ResponseFormat responseFormat) {}

  private record Msg(String role, String content) {}

  private record ResponseFormat(String type, @SerializedName("json_schema") Object jsonSchema) {}

  // ── Response DTOs ─────────────────────────────────────────────────────────

  private record Resp(List<Choice> choices, Usage usage) {}

  private record Choice(Msg message) {}

  private record Usage(
      @SerializedName("prompt_tokens") int promptTokens,
      @SerializedName("completion_tokens") int completionTokens) {}
}
