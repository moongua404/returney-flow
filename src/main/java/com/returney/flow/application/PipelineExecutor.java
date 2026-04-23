package com.returney.flow.application;

import com.returney.flow.domain.execution.ExecutionConfig;
import com.returney.flow.domain.execution.ExecutionContext;
import com.returney.flow.domain.execution.NodeResult;
import com.returney.flow.domain.execution.NodeStatus;
import com.returney.flow.domain.definition.PipelineDefinition;
import com.returney.flow.domain.definition.PipelineNode;
import com.returney.flow.domain.execution.PipelineResult;
import com.returney.flow.port.ExecutionListener;
import com.returney.flow.port.PipelineRunner;
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

/**
 * DAG 기반 파이프라인 스케줄러.
 *
 * <p>토폴로지 정렬로 frontier를 추출하고 병렬 실행한다.
 * 노드 실패 시 해당 다운스트림만 건너뛰고 다른 브랜치는 계속 진행한다.
 * 개별 노드 실행 라이프사이클은 {@link NodeExecutor}가 처리한다.
 */
public class PipelineExecutor implements PipelineRunner {

  private final NodeExecutor nodeExecutor;
  private final ExecutionListener listener;
  private final Executor executor;

  public PipelineExecutor(NodeExecutor nodeExecutor, ExecutionListener listener, Executor executor) {
    this.nodeExecutor = nodeExecutor;
    this.listener = listener;
    this.executor = executor;
  }

  @Override
  public CompletableFuture<PipelineResult> run(
      PipelineDefinition pipelineDef, String sessionId, Set<String> nodeIds, ExecutionConfig config,
      Map<String, NodeResult> seedResults, Map<String, String> prerequisites) {
    return executeInternal(pipelineDef, sessionId, nodeIds, config, seedResults, prerequisites);
  }

  private CompletableFuture<PipelineResult> executeInternal(
      PipelineDefinition pipelineDef, String sessionId, Set<String> targetNodeIds,
      ExecutionConfig config, Map<String, NodeResult> seedResults,
      Map<String, String> prerequisites) {

    ExecutionContext ctx = new ExecutionContext(sessionId, seedResults, prerequisites);
    for (String nodeId : targetNodeIds) {
      ctx.setStatus(nodeId, NodeStatus.PENDING);
    }

    return CompletableFuture.supplyAsync(
        () -> {
          runDag(pipelineDef, ctx, targetNodeIds, config);

          List<String> failedNodes = ctx.allStatuses().entrySet().stream()
              .filter(e -> e.getValue() == NodeStatus.FAILED)
              .map(Map.Entry::getKey)
              .toList();

          PipelineResult result = new PipelineResult(ctx.elapsedMs(), ctx.allResults(), failedNodes);
          listener.onFlowCompleted(result);
          return result;
        },
        executor);
  }

  private void runDag(
      PipelineDefinition pipelineDef,
      ExecutionContext ctx,
      Set<String> targetNodeIds,
      ExecutionConfig config) {

    Set<String> skipSet = ConcurrentHashMap.newKeySet();

    Map<String, Integer> inDegree = new HashMap<>();
    for (String nodeId : targetNodeIds) {
      int degree = 0;
      for (String upstream : pipelineDef.upstreamOf(nodeId)) {
        if (targetNodeIds.contains(upstream)) degree++;
      }
      inDegree.put(nodeId, degree);
    }

    Map<String, CompletableFuture<Void>> completionFutures = new ConcurrentHashMap<>();
    Set<String> processedNodes = new HashSet<>();

    List<String> frontier = new ArrayList<>();
    for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
      if (entry.getValue() == 0) frontier.add(entry.getKey());
    }

    while (!frontier.isEmpty()) {
      List<CompletableFuture<Void>> batch = new ArrayList<>();

      for (String nodeId : frontier) {
        if (skipSet.contains(nodeId)) {
          ctx.setStatus(nodeId, NodeStatus.SKIPPED);
          listener.onNodeSkipped(nodeId);
          markDownstreamSkipped(pipelineDef, nodeId, targetNodeIds, skipSet);
          CompletableFuture<Void> skipped = CompletableFuture.completedFuture(null);
          batch.add(skipped);
          completionFutures.put(nodeId, skipped);
          continue;
        }

        PipelineNode node = pipelineDef.findNode(nodeId).orElseThrow();
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          boolean success = nodeExecutor.execute(node, pipelineDef, ctx, config);
          if (!success) {
            markDownstreamSkipped(pipelineDef, nodeId, targetNodeIds, skipSet);
          }
        }, executor);
        batch.add(future);
        completionFutures.put(nodeId, future);
      }

      CompletableFuture.allOf(batch.toArray(new CompletableFuture[0])).join();

      List<String> justCompleted = new ArrayList<>();
      for (String nodeId : completionFutures.keySet()) {
        if (!processedNodes.contains(nodeId)) {
          justCompleted.add(nodeId);
          processedNodes.add(nodeId);
        }
      }

      frontier = new ArrayList<>();
      for (String completedId : justCompleted) {
        for (String downstream : pipelineDef.downstreamOf(completedId)) {
          if (!targetNodeIds.contains(downstream)) continue;
          if (completionFutures.containsKey(downstream)) continue;
          inDegree.put(downstream, inDegree.get(downstream) - 1);
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

  private void markDownstreamSkipped(
      PipelineDefinition pipelineDef, String nodeId, Set<String> targetNodeIds, Set<String> skipSet) {
    Deque<String> queue = new ArrayDeque<>(pipelineDef.downstreamOf(nodeId));
    while (!queue.isEmpty()) {
      String ds = queue.poll();
      if (targetNodeIds.contains(ds) && skipSet.add(ds)) {
        queue.addAll(pipelineDef.downstreamOf(ds));
      }
    }
  }
}
