package com.returney.flow.application;

import com.returney.flow.domain.definition.NodeType;
import com.returney.flow.domain.definition.PipelineDefinition;
import com.returney.flow.domain.definition.PipelineNode;
import com.returney.flow.domain.execution.ExecutionConfig;
import com.returney.flow.domain.execution.NodeResult;
import com.returney.flow.domain.execution.NodeStatus;
import com.returney.flow.port.ExecutionListener;
import com.returney.flow.port.ServerNodeExecutor;
import java.util.List;
import java.util.Map;

/**
 * 단일 노드 실행 라이프사이클.
 *
 * <p>입력 해석 → 실행 → 결과 저장 → 상태 통보를 담당한다.
 * DAG 스케줄링(frontier 관리, downstream skip)은 {@link PipelineExecutor}가 처리한다.
 */
public class NodeExecutor {

  private final LlmNodeRunner llmNodeRunner;
  private final ServerNodeExecutor serverNodeExecutor;
  private final NodeInputResolver inputResolver;
  private final ExecutionListener listener;

  public NodeExecutor(
      LlmNodeRunner llmNodeRunner,
      ServerNodeExecutor serverNodeExecutor,
      NodeInputResolver inputResolver,
      ExecutionListener listener) {
    this.llmNodeRunner = llmNodeRunner;
    this.serverNodeExecutor = serverNodeExecutor;
    this.inputResolver = inputResolver;
    this.listener = listener;
  }

  /**
   * 노드를 실행한다.
   *
   * @return 성공하면 true, 실패하면 false (ctx와 listener는 이미 업데이트된 상태)
   */
  public boolean execute(
      PipelineNode node, PipelineDefinition pipelineDef, ExecutionContext ctx, ExecutionConfig config) {

    ctx.setStatus(node.id(), NodeStatus.RUNNING);
    listener.onNodeStarted(node.id(), System.currentTimeMillis());

    long start = System.currentTimeMillis();
    try {
      NodeResult result;
      if (node.type() == NodeType.LLM) {
        // LLM 노드는 토큰 정보를 NodeResult에 채워 반환.
        result = llmNodeRunner.runLlm(node, pipelineDef, ctx, config);
      } else {
        String output = switch (node.type()) {
          case TEMPLATE -> llmNodeRunner.runTemplate(node, ctx);
          case SCATTER  -> executeScatter(node, ctx);
          case GATHER   -> executeGather(node, pipelineDef, ctx);
          case TRANSFORM -> executeTransform(node, ctx);
          default -> throw new IllegalStateException("unhandled node type: " + node.type());
        };
        result = new NodeResult(output, System.currentTimeMillis() - start, 0, 0);
      }
      ctx.setResult(node.id(), result);

      ctx.setStatus(node.id(), NodeStatus.COMPLETED);
      listener.onNodeCompleted(node.id(), result);
      return true;

    } catch (Exception e) {
      recordFailure(node, start, e.getMessage(), ctx);
      return false;
    }
  }

  private String executeScatter(PipelineNode node, ExecutionContext ctx) {
    Map<String, String> inputs = inputResolver.resolve(node, ctx);
    List<String> chunks = serverNodeExecutor.scatter(node.id(), inputs);
    List<NodeResult> chunkResults = chunks.stream()
        .map(c -> new NodeResult(c, 0, 0, 0))
        .toList();
    ctx.setScatterResults(node.id(), chunkResults);
    return "[scatter:" + chunks.size() + "]";
  }

  private String executeGather(PipelineNode node, PipelineDefinition pipelineDef, ExecutionContext ctx) {
    List<String> chunks = collectGatherInputChunks(node, pipelineDef, ctx);
    return serverNodeExecutor.gather(node.id(), chunks);
  }

  private List<String> collectGatherInputChunks(
      PipelineNode node, PipelineDefinition pipelineDef, ExecutionContext ctx) {
    for (String upstreamId : pipelineDef.upstreamOf(node.id())) {
      if (ctx.hasScatterResults(upstreamId)) {
        return ctx.getScatterResults(upstreamId).stream()
            .map(NodeResult::output)
            .toList();
      }
    }
    throw new IllegalStateException(
        "GATHER node '" + node.id() + "' has no upstream with scatter results");
  }

  private String executeTransform(PipelineNode node, ExecutionContext ctx) {
    Map<String, String> inputs = inputResolver.resolve(node, ctx);
    return serverNodeExecutor.transform(node.id(), inputs);
  }

  private void recordFailure(PipelineNode node, long start, String message, ExecutionContext ctx) {
    long latencyMs = System.currentTimeMillis() - start;
    ctx.setStatus(node.id(), NodeStatus.FAILED);
    ctx.setResult(node.id(), new NodeResult(null, latencyMs, 0, 0));
    listener.onNodeFailed(node.id(), message);
  }
}
