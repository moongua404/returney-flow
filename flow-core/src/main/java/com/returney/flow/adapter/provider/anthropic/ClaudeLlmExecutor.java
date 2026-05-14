package com.returney.flow.adapter.provider.anthropic;

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
  private final int requestTimeoutSec;

  public ClaudeLlmExecutor(String apiKey) {
    this(apiKey, "https://api.anthropic.com", 10, 300);
  }

  public ClaudeLlmExecutor(String apiKey, String baseUrl) {
    this(apiKey, baseUrl, 10, 300);
  }

  public ClaudeLlmExecutor(String apiKey, String baseUrl, int connectTimeoutSec, int requestTimeoutSec) {
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
    Map<String, String> headers = Map.of("x-api-key", apiKey, "anthropic-version", API_VERSION);
    String responseBody = HttpUtil.postJsonOrThrow(
        httpClient, baseUrl + "/v1/messages", body, headers, PROVIDER, requestTimeoutSec);
    return parseResponse(responseBody);
  }

  /**
   * 단일 user 메시지 + 선택적 system + 선택적 thinking. multi-turn 대화는 호출자가
   * prompt 텍스트에 history를 포함시켜 넘긴다.
   *
   * <p>system이 있고 cacheEnabled면 cache_control: ephemeral 적용 — 같은 system prefix
   * 재사용 시 입력 토큰이 cached로 청구된다.
   *
   * <p>JSON 출력 강제는 prompt + 호출자 단의 fallback wrap에 의존. assistant prefill {@code "{"}을
   * 시도했었으나 sonnet이 즉시 stop을 유발해 빈 content block을 돌려주는 변동성이 있어 revert함.
   * 정도(正道) 처방은 tool-use 강제(별 PR).
   */
  private String buildBody(LlmRequest request) {
    Thinking thinking =
        request.thinkingBudget() > 0 ? new Thinking("enabled", request.thinkingBudget()) : null;
    Object system = null;
    if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
      system = request.cacheEnabled()
          ? List.of(new SystemBlock("text", request.systemPrompt(), new CacheControl("ephemeral")))
          : request.systemPrompt();
    }
    List<Msg> messages = List.of(new Msg("user", request.prompt()));
    return GSON.toJson(new Req(request.model(), resolveMaxTokens(request.model()), system, messages, thinking));
  }

  private LlmRawResponse parseResponse(String rawResponse) {
    Resp resp = GSON.fromJson(rawResponse, Resp.class);
    String text = "";
    if (resp.content() != null) {
      for (Block block : resp.content()) {
        if ("text".equals(block.type())) {
          // stripCodeBlock(null)은 null을 그대로 반환. claude-sonnet-4-6 등에서 silent하게 빈
          // content를 반환하는 케이스(GitHub anthropics/claude-code-action #1243)가 알려져 있어
          // 여기서 null을 ""로 정규화 — 호출자/파서가 NPE 없이 fallback 처리하도록.
          String stripped = HttpUtil.stripCodeBlock(block.text());
          text = stripped != null ? stripped : "";
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
