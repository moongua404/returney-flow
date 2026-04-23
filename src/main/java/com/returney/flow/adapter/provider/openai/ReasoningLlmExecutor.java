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
import java.util.stream.Collectors;

/**
 * OpenAI Reasoning 모델 LlmExecutor 구현체.
 *
 * <p>o1, o3, o4 계열 reasoning 모델을 처리한다.
 * GPT 계열과 달리 system 메시지를 지원하지 않으며,
 * max_tokens 대신 max_completion_tokens를 사용한다.
 */
public class ReasoningLlmExecutor implements LlmExecutor {

  private static final String PROVIDER = "Reasoning";
  private static final Gson GSON = new Gson();
  private static final int MAX_COMPLETION_TOKENS = 16384;

  private final String apiKey;
  private final String baseUrl;
  private final HttpClient httpClient;

  public ReasoningLlmExecutor(String apiKey) {
    this(apiKey, "https://api.openai.com");
  }

  public ReasoningLlmExecutor(String apiKey, String baseUrl) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
    this.httpClient = HttpUtil.newClient();
  }

  @Override
  public LlmRawResponse execute(LlmRequest request) {
    String prompt;
    if (request.isConversation()) {
      // reasoning 모델은 system 미지원 — system + messages를 user 메시지로 병합
      prompt = request.systemPrompt() + "\n\n"
          + request.messages().stream()
              .map(m -> "[" + m.role() + "] " + m.content())
              .collect(Collectors.joining("\n"));
    } else {
      prompt = request.singlePrompt();
    }
    return callApi(buildBody(prompt, request.model()), request.model());
  }

  private LlmRawResponse callApi(String body, String model) {
    Map<String, String> headers = Map.of("Authorization", "Bearer " + apiKey);
    String responseBody = HttpUtil.postJsonOrThrow(httpClient, baseUrl + "/v1/chat/completions", body, headers, PROVIDER);
    return parseResponse(responseBody);
  }

  private String buildBody(String prompt, String model) {
    return GSON.toJson(new Req(model, MAX_COMPLETION_TOKENS,
        List.of(new Msg("user", prompt)), "low", new ResponseFormat("json_object")));
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
      @SerializedName("max_completion_tokens") int maxCompletionTokens,
      List<Msg> messages,
      @SerializedName("reasoning_effort") String reasoningEffort,
      @SerializedName("response_format") ResponseFormat responseFormat) {}

  private record Msg(String role, String content) {}

  private record ResponseFormat(String type) {}

  // ── Response DTOs ─────────────────────────────────────────────────────────

  private record Resp(List<Choice> choices, Usage usage) {}

  private record Choice(Msg message) {}

  private record Usage(
      @SerializedName("prompt_tokens") int promptTokens,
      @SerializedName("completion_tokens") int completionTokens) {}
}
