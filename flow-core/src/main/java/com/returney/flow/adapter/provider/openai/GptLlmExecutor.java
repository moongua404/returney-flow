package com.returney.flow.adapter.provider.openai;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.returney.flow.adapter.common.HttpUtil;
import com.returney.flow.port.LlmExecutor;
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

  public GptLlmExecutor(String apiKey) {
    this(apiKey, "https://api.openai.com");
  }

  public GptLlmExecutor(String apiKey, String baseUrl) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
    this.httpClient = HttpUtil.newClient();
  }

  @Override
  public LlmRawResponse execute(LlmRequest request) {
    return callApi(buildBody(request.singlePrompt(), request.model(), null, null), request.model());
  }

  /**
   * responseSchema가 있으면 Structured Outputs(json_schema)로 요청한다.
   */
  public LlmRawResponse executeWithSchema(
      String renderedPrompt, String model, String action, Map<String, Object> responseSchema) {
    return callApi(buildBody(renderedPrompt, model, responseSchema, action), model);
  }

  private LlmRawResponse callApi(String body, String model) {
    Map<String, String> headers = Map.of("Authorization", "Bearer " + apiKey);
    String responseBody = HttpUtil.postJsonOrThrow(httpClient, baseUrl + "/v1/chat/completions", body, headers, PROVIDER);
    return parseResponse(responseBody);
  }

  private String buildBody(String prompt, String model, Map<String, Object> responseSchema, String action) {
    ResponseFormat format = (responseSchema != null && action != null)
        ? new ResponseFormat("json_schema", new JsonSchemaWrapper(action + "_response", true, responseSchema))
        : new ResponseFormat("json_object", null);
    return GSON.toJson(new Req(model, MAX_TOKENS, List.of(new Msg("user", prompt)), format));
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

  private record ResponseFormat(String type,
      @SerializedName("json_schema") JsonSchemaWrapper jsonSchema) {}

  private record JsonSchemaWrapper(String name, boolean strict, Object schema) {}

  // ── Response DTOs ─────────────────────────────────────────────────────────

  private record Resp(List<Choice> choices, Usage usage) {}

  private record Choice(Msg message) {}

  private record Usage(
      @SerializedName("prompt_tokens") int promptTokens,
      @SerializedName("completion_tokens") int completionTokens) {}
}
