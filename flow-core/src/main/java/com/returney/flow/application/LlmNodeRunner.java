package com.returney.flow.application;

import com.google.gson.Gson;
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
import com.returney.flow.util.LlmJsonExtractor;
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
      return NodeResult.of(marker, 0, 0, 0);
    }
    Map<String, String> variables = inputResolver.resolve(node, ctx);
    long start = System.currentTimeMillis();
    LlmRawResponse resp = callLlm(node, variables, config, parseSessionId(ctx));
    long latency = System.currentTimeMillis() - start;
    Object typed = deserialize(node, resp.text());
    return new NodeResult(resp.text(), typed, latency, resp.inputTokens(), resp.outputTokens());
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

  /**
   * 단일 모드 LlmRequest 빌드. flow는 대화 상태를 모르므로 multi-turn 챗 파이프라인은 호출자가
   * yaml 변수에 history 텍스트를 미리 인터폴레이션해 넘긴다 (예: {@code conversationHistory}).
   *
   * <p>node.resultType이 record면 {@link com.returney.flow.util.SchemaRenderer}가 만든
   * 평문 schema를 user prompt 끝에 자동 첨부한다 — yaml prompt에 출력 형식을 손으로 박을 필요 없음.
   */
  private LlmRequest buildRequest(
      PipelineNode node, Map<String, String> variables, String model, int budget) {
    String prompt = promptRenderer.render(node.action(), variables);
    String schemaHint = renderSchemaHint(node);
    if (schemaHint != null) prompt = prompt + schemaHint;

    String systemPrompt = promptRenderer.renderSystemPrompt(node.action(), variables);
    if (systemPrompt != null && systemPrompt.isBlank()) systemPrompt = null;

    String binaryB64 = variables.get("binaryContentBase64");
    String mimeType = variables.get("mimeType");
    boolean hasBinary =
        binaryB64 != null && !binaryB64.isEmpty() && mimeType != null && !mimeType.isEmpty();

    return new LlmRequest(
        model,
        budget,
        systemPrompt,
        prompt,
        true,
        hasBinary ? Base64.getDecoder().decode(binaryB64) : null,
        hasBinary ? mimeType : null);
  }

  /**
   * node.resultType이 record class면 SchemaRenderer로 평문 hint 생성. String/builtin/null이면
   * null 반환 (수동 yaml 출력 블록을 쓰는 노드는 그대로 동작).
   */
  private String renderSchemaHint(PipelineNode node) {
    String fqcn = node.resultType();
    if (fqcn == null || fqcn.isBlank()) return null;
    Class<?> type;
    try {
      type = Class.forName(fqcn);
    } catch (ClassNotFoundException e) {
      return null;
    }
    return com.returney.flow.util.SchemaRenderer.renderAsPrompt(type);
  }

  /**
   * LLM 응답 텍스트를 node.resultType으로 역직렬화한다.
   *
   * <p>resultType이 없거나 String이면 null 반환 (raw output 그대로 사용). 역직렬화 실패 시 null 폴백
   * — 기존 Gson-based parseResult()가 raw output으로 처리한다.
   */
  private static Object deserialize(PipelineNode node, String text) {
    String fqcn = node.resultType();
    if (fqcn == null || fqcn.isBlank() || "java.lang.String".equals(fqcn)) return null;
    try {
      Class<?> type = Class.forName(fqcn);
      if (!type.isRecord() && !type.isArray()) return null;
      return GSON.fromJson(LlmJsonExtractor.extract(text), type);
    } catch (Exception e) {
      return null;
    }
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
          Object typed = deserialize(node, resp.text());
          return new NodeResult(
              resp.text(), typed, System.currentTimeMillis() - t,
              resp.inputTokens(), resp.outputTokens());
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
