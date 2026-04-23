package com.returney.flow.application;

import com.returney.flow.domain.definition.PipelineDefinition;
import com.returney.flow.domain.definition.PipelineNode;
import com.returney.flow.domain.execution.ExecutionConfig;
import com.returney.flow.domain.execution.ExecutionContext;
import com.returney.flow.domain.execution.NodeResult;
import com.returney.flow.domain.llm.LlmCallException;
import com.returney.flow.domain.llm.LlmRequest;
import com.returney.flow.port.LlmExecutor;
import com.returney.flow.port.PromptRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** LLM 노드 및 TEMPLATE 노드 실행. fan-out(scatter 병렬화) 포함. */
public class LlmNodeRunner {

  private final LlmExecutor llmExecutor;
  private final PromptRenderer promptRenderer;
  private final NodeInputResolver inputResolver;
  private final Executor executor;

  public LlmNodeRunner(
      LlmExecutor llmExecutor,
      PromptRenderer promptRenderer,
      NodeInputResolver inputResolver,
      Executor executor) {
    this.llmExecutor = llmExecutor;
    this.promptRenderer = promptRenderer;
    this.inputResolver = inputResolver;
    this.executor = executor;
  }

  String runLlm(
      PipelineNode node, PipelineDefinition pipelineDef,
      ExecutionContext ctx, ExecutionConfig config) throws LlmCallException {
    String scatterUpstream = findScatterUpstream(node, pipelineDef, ctx);
    if (scatterUpstream != null) {
      return executeFanOut(node, ctx, config, scatterUpstream);
    }
    Map<String, String> variables = inputResolver.resolve(node, ctx);
    return callLlm(node, variables, config);
  }

  String runTemplate(PipelineNode node, ExecutionContext ctx) {
    Map<String, String> variables = inputResolver.resolve(node, ctx);
    return promptRenderer.render(node.action(), variables);
  }

  // ── private ───────────────────────────────────────────────────────────────

  private String callLlm(
      PipelineNode node, Map<String, String> variables, ExecutionConfig config)
      throws LlmCallException {
    String model = config.resolveModel(promptRenderer.getModel(node.action()));
    int budget = config.resolveThinkingBudget(promptRenderer.getThinkingBudget(node.action()));
    llmExecutor.setContext(node.action(), variables);
    return llmExecutor.execute(buildRequest(node, variables, model, budget)).text();
  }

  private LlmRequest buildRequest(
      PipelineNode node, Map<String, String> variables, String model, int budget) {
    String systemPrompt = promptRenderer.renderSystemPrompt(node.action(), variables);
    if (systemPrompt != null) {
      String userPrompt = promptRenderer.renderUserPrompt(node.action(), variables);
      return LlmRequest.conversation(
          systemPrompt,
          List.of(new LlmRequest.Message("user", userPrompt)),
          model, budget, new LlmRequest.CacheConfig(true));
    }
    return LlmRequest.single(promptRenderer.render(node.action(), variables), model, budget);
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

    for (NodeResult chunk : chunks) {
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          Map<String, String> variables = inputResolver.resolve(node, ctx);
          variables.put("chunk", chunk.output());
          long t = System.currentTimeMillis();
          String output = callLlm(node, variables, config);
          return new NodeResult(output, System.currentTimeMillis() - t, 0, 0);
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
