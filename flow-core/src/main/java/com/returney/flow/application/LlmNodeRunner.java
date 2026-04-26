package com.returney.flow.application;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.returney.flow.domain.definition.PipelineDefinition;
import com.returney.flow.domain.definition.PipelineNode;
import com.returney.flow.domain.execution.ExecutionConfig;
import com.returney.flow.domain.execution.NodeResult;
import com.returney.flow.domain.llm.LlmCallContext;
import com.returney.flow.domain.llm.LlmCallException;
import com.returney.flow.domain.llm.LlmRawResponse;
import com.returney.flow.domain.llm.LlmRequest;
import com.returney.flow.port.ExecutionListener;
import com.returney.flow.port.LlmExecutor;
import com.returney.flow.port.PromptRenderer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** LLM 노드 및 TEMPLATE 노드 실행. fan-out(scatter 병렬화) 포함. */
public class LlmNodeRunner {

  private static final Gson GSON = new Gson();

  private final LlmExecutor llmExecutor;
  private final PromptRenderer promptRenderer;
  private final NodeInputResolver inputResolver;
  private final Executor executor;
  private final ExecutionListener listener;

  public LlmNodeRunner(
      LlmExecutor llmExecutor,
      PromptRenderer promptRenderer,
      NodeInputResolver inputResolver,
      Executor executor,
      ExecutionListener listener) {
    this.llmExecutor = llmExecutor;
    this.promptRenderer = promptRenderer;
    this.inputResolver = inputResolver;
    this.executor = executor;
    this.listener = listener;
  }

  /**
   * LLM 노드 실행. fan-out 노드의 경우 NodeResult.output()은 더미("[fan-out:N]")이고
   * 토큰은 0 — 실제 응답은 ctx.scatterResults에 자식 NodeResult별로 적재된다.
   */
  NodeResult runLlm(
      PipelineNode node, PipelineDefinition pipelineDef,
      ExecutionContext ctx, ExecutionConfig config) throws LlmCallException {
    String scatterUpstream = findScatterUpstream(node, pipelineDef, ctx);
    if (scatterUpstream != null) {
      String marker = executeFanOut(node, ctx, config, scatterUpstream);
      return new NodeResult(marker, 0, 0, 0);
    }
    Map<String, String> variables = inputResolver.resolve(node, ctx);
    long start = System.currentTimeMillis();
    LlmRawResponse resp = callLlm(node, variables, config, parseSessionId(ctx));
    long latency = System.currentTimeMillis() - start;
    return new NodeResult(resp.text(), latency, resp.inputTokens(), resp.outputTokens());
  }

  String runTemplate(PipelineNode node, ExecutionContext ctx) {
    Map<String, String> variables = inputResolver.resolve(node, ctx);
    return promptRenderer.render(node.action(), variables);
  }

  // ── private ───────────────────────────────────────────────────────────────

  /** ExecutionContext의 sessionId(String) → UUID. fan-out 자식 스레드에서 ThreadLocal 재설정에 필요. */
  private static UUID parseSessionId(ExecutionContext ctx) {
    String sid = ctx.sessionId();
    if (sid == null || sid.isBlank()) return null;
    try {
      return UUID.fromString(sid);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private LlmRawResponse callLlm(
      PipelineNode node, Map<String, String> variables, ExecutionConfig config,
      UUID sessionId) throws LlmCallException {
    String model = config.resolveModel(promptRenderer.getModel(node.action()));
    int budget = config.resolveThinkingBudget(promptRenderer.getThinkingBudget(node.action()));
    LlmCallContext callContext = new LlmCallContext(sessionId, node.action(), variables, listener);
    return llmExecutor.execute(buildRequest(node, variables, model, budget), callContext);
  }

  private LlmRequest buildRequest(
      PipelineNode node, Map<String, String> variables, String model, int budget) {
    String binaryB64 = variables.get("binaryContentBase64");
    String mimeType = variables.get("mimeType");
    if (binaryB64 != null && !binaryB64.isEmpty()
        && mimeType != null && !mimeType.isEmpty()) {
      String prompt = promptRenderer.render(node.action(), variables);
      return LlmRequest.multimodal(
          prompt, Base64.getDecoder().decode(binaryB64), mimeType, model, budget);
    }
    String systemPrompt = promptRenderer.renderSystemPrompt(node.action(), variables);
    if (systemPrompt != null) {
      List<LlmRequest.Message> messages = parseMessagesOrSynthesize(node, variables);
      return LlmRequest.conversation(
          systemPrompt, messages, model, budget, new LlmRequest.CacheConfig(true));
    }
    return LlmRequest.single(promptRenderer.render(node.action(), variables), model, budget);
  }

  private List<LlmRequest.Message> parseMessagesOrSynthesize(
      PipelineNode node, Map<String, String> variables) {
    String messagesJson = variables.get("conversationMessagesJson");
    if (messagesJson != null && !messagesJson.isEmpty()) {
      List<Map<String, String>> raw = GSON.fromJson(
          messagesJson, new TypeToken<List<Map<String, String>>>() {}.getType());
      List<LlmRequest.Message> messages = new ArrayList<>(raw.size());
      for (Map<String, String> m : raw) {
        messages.add(new LlmRequest.Message(
            m.getOrDefault("role", "user"), m.getOrDefault("content", "")));
      }
      return messages;
    }
    String userPrompt = promptRenderer.renderUserPrompt(node.action(), variables);
    return List.of(new LlmRequest.Message("user", userPrompt));
  }

  private String findScatterUpstream(
      PipelineNode node, PipelineDefinition pipelineDef, ExecutionContext ctx) {
    for (String upstreamId : pipelineDef.upstreamOf(node.id())) {
      if (ctx.hasScatterResults(upstreamId)) return upstreamId;
    }
    return null;
  }

  private String executeFanOut(
      PipelineNode node, ExecutionContext ctx, ExecutionConfig config, String scatterUpstreamId) {
    List<NodeResult> chunks = ctx.getScatterResults(scatterUpstreamId);
    List<CompletableFuture<NodeResult>> futures = new ArrayList<>();
    // 자식 스레드(virtual thread)에 ThreadLocal이 상속되지 않으므로 sessionId를
    // 캡처해서 callLlm으로 명시적 전달.
    UUID sessionId = parseSessionId(ctx);

    for (NodeResult chunk : chunks) {
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          Map<String, String> variables = inputResolver.resolve(node, ctx);
          variables.put("chunk", chunk.output());
          long t = System.currentTimeMillis();
          LlmRawResponse resp = callLlm(node, variables, config, sessionId);
          return new NodeResult(
              resp.text(), System.currentTimeMillis() - t, resp.inputTokens(), resp.outputTokens());
        } catch (LlmCallException e) {
          throw new RuntimeException(e);
        }
      }, executor));
    }

    List<NodeResult> results = futures.stream()
        .map(CompletableFuture::join)
        .toList();

    ctx.setScatterResults(node.id(), results);
    // DAG 완료 감지용 더미 결과 — 실제 출력은 scatterResults에서 읽음
    return "[fan-out:" + results.size() + "]";
  }
}
