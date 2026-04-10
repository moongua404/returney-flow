package com.returney.flow.engine;

import com.returney.flow.core.FlowDefinition;
import com.returney.flow.core.FlowNode;
import com.returney.flow.core.NodeType;
import com.returney.flow.spi.DataStore;
import com.returney.flow.spi.ExecutionListener;
import com.returney.flow.spi.InputResolver;
import com.returney.flow.spi.LlmExecutor;
import com.returney.flow.spi.LlmRequest;
import com.returney.flow.spi.PromptRenderer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * DAG 기반 플로우 실행 엔진.
 *
 * <p>토폴로지 정렬로 frontier를 추출하고, 병렬 실행 가능한 노드를 동시에 실행한다. 노드 실패 시 해당 노드의
 * 다운스트림만 건너뛰고 다른 브랜치는 계속 실행한다.
 */
public class FlowExecutor {

  private final LlmExecutor llmExecutor;
  private final PromptRenderer promptRenderer;
  private final DataStore dataStore;
  private final InputResolver inputResolver;
  private final ExecutionListener listener;
  private final Executor executor;

  public FlowExecutor(
      LlmExecutor llmExecutor,
      PromptRenderer promptRenderer,
      DataStore dataStore,
      InputResolver inputResolver,
      ExecutionListener listener,
      Executor executor) {
    this.llmExecutor = llmExecutor;
    this.promptRenderer = promptRenderer;
    this.dataStore = dataStore;
    this.inputResolver = inputResolver;
    this.listener = listener;
    this.executor = executor;
  }

  /** 전체 플로우를 실행한다. */
  public CompletableFuture<FlowResult> executeAll(
      FlowDefinition flowDef, String sessionId, ExecutionConfig config) {
    Set<String> allNodeIds = flowDef.nodeIds();
    return executeInternal(flowDef, sessionId, allNodeIds, config);
  }

  /** 선택된 노드만 실행한다 (의존성 무시, 이미 완료된 upstream의 결과 사용). */
  public CompletableFuture<FlowResult> executeNodes(
      FlowDefinition flowDef, String sessionId, Set<String> nodeIds, ExecutionConfig config) {
    return executeInternal(flowDef, sessionId, nodeIds, config);
  }

  /** 지정 노드 + 모든 다운스트림을 실행한다. */
  public CompletableFuture<FlowResult> executeFromNode(
      FlowDefinition flowDef, String sessionId, String startNodeId, ExecutionConfig config) {
    Set<String> targets = collectDownstream(flowDef, startNodeId);
    targets.add(startNodeId);
    return executeInternal(flowDef, sessionId, targets, config);
  }

  private CompletableFuture<FlowResult> executeInternal(
      FlowDefinition flowDef, String sessionId, Set<String> targetNodeIds, ExecutionConfig config) {

    ExecutionContext ctx = new ExecutionContext(sessionId);

    // 대상 노드를 PENDING으로 초기화
    for (String nodeId : targetNodeIds) {
      ctx.setStatus(nodeId, NodeStatus.PENDING);
    }

    return CompletableFuture.supplyAsync(
        () -> {
          runDag(flowDef, ctx, targetNodeIds, config);

          List<String> failedNodes =
              ctx.allStatuses().entrySet().stream()
                  .filter(e -> e.getValue() == NodeStatus.FAILED)
                  .map(Map.Entry::getKey)
                  .toList();

          FlowResult result = new FlowResult(ctx.elapsedMs(), ctx.allResults(), failedNodes);
          listener.onFlowCompleted(result);
          return result;
        },
        executor);
  }

  private void runDag(
      FlowDefinition flowDef,
      ExecutionContext ctx,
      Set<String> targetNodeIds,
      ExecutionConfig config) {

    // 실패로 인해 건너뛸 노드 추적
    Set<String> skipSet = ConcurrentHashMap.newKeySet();

    // in-degree 계산 (대상 노드 간의 엣지만)
    Map<String, Integer> inDegree = new HashMap<>();
    for (String nodeId : targetNodeIds) {
      int degree = 0;
      for (String upstream : flowDef.upstreamOf(nodeId)) {
        if (targetNodeIds.contains(upstream)) {
          degree++;
        }
      }
      inDegree.put(nodeId, degree);
    }

    // 완료 추적
    Map<String, CompletableFuture<Void>> completionFutures = new ConcurrentHashMap<>();
    Set<String> processedNodes = new HashSet<>();

    // frontier 추출 (in-degree == 0)
    List<String> frontier = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
      if (entry.getValue() == 0) {
        frontier.add(entry.getKey());
      }
    }

    while (!frontier.isEmpty()) {
      List<CompletableFuture<Void>> batch = new ArrayList<>();

      for (String nodeId : frontier) {
        if (skipSet.contains(nodeId)) {
          System.out.println("[FlowExecutor] SKIPPED: " + nodeId);
          ctx.setStatus(nodeId, NodeStatus.SKIPPED);
          markDownstreamSkipped(flowDef, nodeId, targetNodeIds, skipSet);
          CompletableFuture<Void> skippedFuture = CompletableFuture.completedFuture(null);
          batch.add(skippedFuture);
          completionFutures.put(nodeId, skippedFuture);
          continue;
        }

        CompletableFuture<Void> future =
            CompletableFuture.runAsync(
                () -> executeNode(flowDef, ctx, nodeId, config, skipSet, targetNodeIds), executor);
        batch.add(future);
        completionFutures.put(nodeId, future);
      }

      // 현재 batch 모두 완료 대기
      CompletableFuture.allOf(batch.toArray(new CompletableFuture[0])).join();

      // 다음 frontier 계산 — 이번 배치에서 새로 완료된 노드만 처리
      List<String> justCompleted = new ArrayList<>();
      for (String nodeId : completionFutures.keySet()) {
        if (!processedNodes.contains(nodeId)) {
          justCompleted.add(nodeId);
          processedNodes.add(nodeId);
        }
      }

      frontier = new ArrayList<>();
      for (String completedId : justCompleted) {
        for (String downstream : flowDef.downstreamOf(completedId)) {
          if (!targetNodeIds.contains(downstream)) continue;
          if (completionFutures.containsKey(downstream)) continue;

          int newDegree = inDegree.get(downstream) - 1;
          inDegree.put(downstream, newDegree);
        }
      }

      for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
        String nodeId = entry.getKey();
        if (entry.getValue() == 0 && !completionFutures.containsKey(nodeId)) {
          frontier.add(nodeId);
        }
      }
    }
  }

  private void executeNode(
      FlowDefinition flowDef,
      ExecutionContext ctx,
      String nodeId,
      ExecutionConfig config,
      Set<String> skipSet,
      Set<String> targetNodeIds) {

    FlowNode node = flowDef.findNode(nodeId).orElseThrow();
    ctx.setStatus(nodeId, NodeStatus.RUNNING);
    listener.onNodeStarted(nodeId, System.currentTimeMillis());
    System.out.println("[FlowExecutor] executeNode START: " + nodeId + " (type=" + node.type() + ", action=" + node.action() + ")");

    long start = System.currentTimeMillis();
    try {
      Map<String, String> variables = resolveInputs(flowDef, ctx, nodeId);
      System.out.println("[FlowExecutor] " + nodeId + " resolved " + variables.size() + " variables: " + variables.keySet());
      String renderedPrompt = promptRenderer.render(node.action(), variables);
      System.out.println("[FlowExecutor] " + nodeId + " rendered prompt: " + renderedPrompt.length() + " chars");

      // 입력 캡처: variables + renderedPrompt를 DataStore에 저장
      String inputJson = buildInputJson(renderedPrompt, config.model(), variables);
      dataStore.save(ctx.sessionId(), nodeId + "_input", inputJson);

      String output;
      if (node.type() == NodeType.LLM) {
        // 우선순위: config 오버라이드 > yaml model > 기본값
        String model = config.model();
        if (model == null) model = promptRenderer.getModel(node.action());
        if (model == null) model = "gemini-2.5-flash";
        int thinkingBudget = config.thinkingBudget();
        if (thinkingBudget < 0) thinkingBudget = promptRenderer.getThinkingBudget(node.action());
        System.out.println("[FlowExecutor] " + nodeId + " calling LLM (model=" + model + ")");
        llmExecutor.setContext(node.action(), variables);
        output = llmExecutor.execute(LlmRequest.single(renderedPrompt, model, thinkingBudget));
        System.out.println("[FlowExecutor] " + nodeId + " LLM response: " + output.length() + " chars");
      } else {
        System.out.println("[FlowExecutor] " + nodeId + " TRANSFORM (no LLM call)");
        output = renderedPrompt;
      }

      long latencyMs = System.currentTimeMillis() - start;

      // 결과 저장
      dataStore.save(ctx.sessionId(), nodeId, output);

      NodeResult result = new NodeResult(output, latencyMs, 0, 0);
      ctx.setResult(nodeId, result);
      ctx.setStatus(nodeId, NodeStatus.COMPLETED);
      listener.onNodeCompleted(nodeId, result);
      System.out.println("[FlowExecutor] executeNode COMPLETE: " + nodeId + " (" + latencyMs + "ms)");

    } catch (Exception e) {
      long latencyMs = System.currentTimeMillis() - start;
      System.out.println("[FlowExecutor] executeNode FAILED: " + nodeId + " — " + e.getMessage());
      e.printStackTrace();
      ctx.setStatus(nodeId, NodeStatus.FAILED);
      ctx.setResult(nodeId, new NodeResult(null, latencyMs, 0, 0));
      listener.onNodeFailed(nodeId, e.getMessage());

      // 다운스트림 skip
      markDownstreamSkipped(flowDef, nodeId, targetNodeIds, skipSet);
    }
  }

  private Map<String, String> resolveInputs(
      FlowDefinition flowDef, ExecutionContext ctx, String nodeId) {
    Map<String, String> variables = new HashMap<>();

    Map<String, String> declaredInputs = flowDef.inputsOf(nodeId);

    if (!declaredInputs.isEmpty()) {
      // inputs 매핑이 정의된 경우: 변수명 → 소스로 resolve
      for (Map.Entry<String, String> entry : declaredInputs.entrySet()) {
        String varName = entry.getKey();
        String source = entry.getValue();
        String value = resolveSource(ctx, source);
        if (value != null) {
          variables.put(varName, value);
        }
      }
    } else {
      // 하위 호환: inputs 미정의 시 upstream 노드 ID를 변수명으로 사용
      for (String upstream : flowDef.upstreamOf(nodeId)) {
        NodeResult upResult = ctx.getResult(upstream);
        if (upResult != null && upResult.output() != null) {
          variables.put(upstream, upResult.output());
        } else {
          String cached = dataStore.load(ctx.sessionId(), upstream);
          if (cached != null) {
            variables.put(upstream, cached);
          }
        }
      }
    }
    return variables;
  }

  private String resolveSource(ExecutionContext ctx, String source) {
    if (source.startsWith("pre:")) {
      // 전처리 데이터: DataStore에서 "pre_xxx" 키로 로드
      return dataStore.load(ctx.sessionId(), source);
    } else {
      // upstream 노드 output
      NodeResult result = ctx.getResult(source);
      if (result != null && result.output() != null) {
        return result.output();
      }
      return dataStore.load(ctx.sessionId(), source);
    }
  }

  private void markDownstreamSkipped(
      FlowDefinition flowDef, String nodeId, Set<String> targetNodeIds, Set<String> skipSet) {
    Deque<String> queue = new ArrayDeque<>(flowDef.downstreamOf(nodeId));
    while (!queue.isEmpty()) {
      String ds = queue.poll();
      if (targetNodeIds.contains(ds) && skipSet.add(ds)) {
        queue.addAll(flowDef.downstreamOf(ds));
      }
    }
  }

  private Set<String> collectDownstream(FlowDefinition flowDef, String startNodeId) {
    Set<String> result = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>(flowDef.downstreamOf(startNodeId));
    while (!queue.isEmpty()) {
      String ds = queue.poll();
      if (result.add(ds)) {
        queue.addAll(flowDef.downstreamOf(ds));
      }
    }
    return result;
  }

  private String buildInputJson(String renderedPrompt, String model, Map<String, String> variables) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"renderedPrompt\":");
    sb.append(escapeJson(renderedPrompt));
    sb.append(",\"model\":");
    sb.append(model != null ? escapeJson(model) : "null");
    sb.append(",\"variables\":{");
    boolean first = true;
    for (Map.Entry<String, String> e : variables.entrySet()) {
      if (!first) sb.append(',');
      sb.append(escapeJson(e.getKey())).append(':').append(escapeJson(e.getValue()));
      first = false;
    }
    sb.append("}}");
    return sb.toString();
  }

  private static String escapeJson(String s) {
    if (s == null) return "null";
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> sb.append(c);
      }
    }
    sb.append('"');
    return sb.toString();
  }
}
