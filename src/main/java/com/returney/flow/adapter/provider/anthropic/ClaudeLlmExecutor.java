package com.returney.flow.adapter.provider.anthropic;

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
 * Anthropic Claude API LlmExecutor 구현체.
 *
 * <p>Sonnet / Haiku / Opus 모든 Claude 모델을 처리한다.
 * thinking budget이 지정되면 extended_thinking을 활성화한다.
 * 대화형 모드에서는 system prompt에 cache_control을 적용하여 멀티턴 캐싱을 지원한다.
 */
public class ClaudeLlmExecutor implements LlmExecutor {

  private static final String PROVIDER = "Claude";
  private static final String API_VERSION = "2023-06-01";
  private static final String CLAUDE_HAIKU_4_5 = "claude-haiku-4-5";
  private static final Gson GSON = new Gson();

  private final String apiKey;
  private final String baseUrl;
  private final HttpClient httpClient;

  public ClaudeLlmExecutor(String apiKey) {
    this(apiKey, "https://api.anthropic.com");
  }

  public ClaudeLlmExecutor(String apiKey, String baseUrl) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
    this.httpClient = HttpUtil.newClient();
  }

  @Override
  public LlmRawResponse execute(LlmRequest request) {
    if (request.isConversation()) {
      boolean enableCache = request.cache() != null && request.cache().enabled();
      return callApi(buildConversationBody(request.systemPrompt(), request.messages(), request.model(), enableCache), request.model());
    }
    return callApi(buildSingleBody(request.singlePrompt(), request.model(), request.thinkingBudget()), request.model());
  }

  private LlmRawResponse callApi(String body, String model) {
    Map<String, String> headers = Map.of("x-api-key", apiKey, "anthropic-version", API_VERSION);
    String responseBody = HttpUtil.postJsonOrThrow(httpClient, baseUrl + "/v1/messages", body, headers, PROVIDER);
    return parseResponse(responseBody);
  }

  private String buildSingleBody(String prompt, String model, int thinkingBudget) {
    Thinking thinking = thinkingBudget > 0 ? new Thinking("enabled", thinkingBudget) : null;
    return GSON.toJson(new Req(model, resolveMaxTokens(model), null, List.of(new Msg("user", prompt)), thinking));
  }

  private String buildConversationBody(
      String systemPrompt, List<LlmRequest.Message> messages, String model, boolean enableCache) {
    Object system = enableCache
        ? List.of(new SystemBlock("text", systemPrompt, new CacheControl("ephemeral")))
        : systemPrompt;
    List<Msg> apiMessages = messages.stream().map(m -> new Msg(m.role(), m.content())).toList();
    return GSON.toJson(new Req(model, resolveMaxTokens(model), system, apiMessages, null));
  }

  private LlmRawResponse parseResponse(String rawResponse) {
    Resp resp = GSON.fromJson(rawResponse, Resp.class);
    String text = "";
    if (resp.content() != null) {
      for (Block block : resp.content()) {
        if ("text".equals(block.type())) {
          text = HttpUtil.stripCodeBlock(block.text());
          break;
        }
      }
    }
    Usage u = resp.usage() != null ? resp.usage() : new Usage(0, 0, 0, 0);
    return new LlmRawResponse(text, u.inputTokens(), u.outputTokens(), 0,
        u.cacheCreationInputTokens(), u.cacheReadInputTokens());
  }

  private int resolveMaxTokens(String model) {
    if (CLAUDE_HAIKU_4_5.equals(model)) return 64_000;
    return 65_536;
  }

  // ── Request DTOs ──────────────────────────────────────────────────────────

  private record Req(
      String model,
      @SerializedName("max_tokens") int maxTokens,
      Object system,
      List<Msg> messages,
      Thinking thinking) {}

  private record Msg(String role, String content) {}

  private record Thinking(String type, @SerializedName("budget_tokens") int budgetTokens) {}

  private record SystemBlock(String type, String text,
      @SerializedName("cache_control") CacheControl cacheControl) {}

  private record CacheControl(String type) {}

  // ── Response DTOs ─────────────────────────────────────────────────────────

  private record Resp(List<Block> content, Usage usage) {}

  private record Block(String type, String text) {}

  private record Usage(
      @SerializedName("input_tokens") int inputTokens,
      @SerializedName("output_tokens") int outputTokens,
      @SerializedName("cache_creation_input_tokens") int cacheCreationInputTokens,
      @SerializedName("cache_read_input_tokens") int cacheReadInputTokens) {}
}
