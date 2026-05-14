package com.returney.flow.application;

import com.returney.flow.domain.definition.PipelineDefinition;
import com.returney.flow.domain.definition.PipelineNode;
import com.returney.flow.domain.execution.ExecutionConfig;
import com.returney.flow.domain.execution.NodeResult;
import com.returney.flow.domain.execution.NodeStatus;
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
 * <p>Kahn's algorithm으로 in-degree=0 frontier를 추출하고 병렬 실행한다.
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
      PipelineDefinition pipelineDef, String sessionId, Set<String> targetNodeIds,
      ExecutionConfig config, Map<String, NodeResult> seedResults,
      Map<String, Object> prerequisites) {

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

    Map<String, Integer> indegree = computeIndegree(pipelineDef, targetNodeIds);
    Set<String> skipSet = ConcurrentHashMap.newKeySet();
    Set<String> scheduled = new HashSet<>();

    while (true) {
      List<String> frontier = new ArrayList<>();
      for (Map.Entry<String, Integer> e : indegree.entrySet()) {
        if (e.getValue() == 0 && !scheduled.contains(e.getKey())) {
          frontier.add(e.getKey());
        }
      }
      if (frontier.isEmpty()) break;

      List<CompletableFuture<Void>> batch = new ArrayList<>(frontier.size());
      for (String nodeId : frontier) {
        scheduled.add(nodeId);
        batch.add(scheduleNode(nodeId, pipelineDef, ctx, config, skipSet, targetNodeIds));
      }
      CompletableFuture.allOf(batch.toArray(new CompletableFuture[0])).join();

      // frontier 노드들이 모두 완료됨 — 다운스트림의 indegree를 감소시켜 다음 frontier 후보로
      for (String done : frontier) {
        for (String ds : pipelineDef.downstreamOf(done)) {
          if (targetNodeIds.contains(ds) && !scheduled.contains(ds)) {
            indegree.computeIfPresent(ds, (k, v) -> v - 1);
          }
        }
      }
    }
  }

  private CompletableFuture<Void> scheduleNode(
      String nodeId, PipelineDefinition pipelineDef, ExecutionContext ctx,
      ExecutionConfig config, Set<String> skipSet, Set<String> targetNodeIds) {

    if (skipSet.contains(nodeId)) {
      ctx.setStatus(nodeId, NodeStatus.SKIPPED);
      listener.onNodeSkipped(nodeId);
      markDownstreamSkipped(pipelineDef, nodeId, targetNodeIds, skipSet);
      return CompletableFuture.completedFuture(null);
    }

    PipelineNode node = pipelineDef.findNode(nodeId).orElseThrow();
    return CompletableFuture.runAsync(() -> {
      boolean success = nodeExecutor.execute(node, pipelineDef, ctx, config);
      if (!success) {
        markDownstreamSkipped(pipelineDef, nodeId, targetNodeIds, skipSet);
      }
    }, executor);
  }

  private static Map<String, Integer> computeIndegree(
      PipelineDefinition pipelineDef, Set<String> targetNodeIds) {
    Map<String, Integer> indegree = new HashMap<>();
    for (String nodeId : targetNodeIds) {
      int degree = 0;
      for (String upstream : pipelineDef.upstreamOf(nodeId)) {
        if (targetNodeIds.contains(upstream)) degree++;
      }
      indegree.put(nodeId, degree);
    }
    return indegree;
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
