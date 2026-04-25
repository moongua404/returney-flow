package com.returney.flow.domain.execution;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 플로우 실행 중 상태를 추적하는 컨텍스트.
 *
 * <p>스레드 안전하게 구현되어 병렬 노드 실행 시 안전하게 공유된다.
 */
public class ExecutionContext {

  private final String sessionId;
  private final Map<String, NodeStatus> nodeStatuses = new ConcurrentHashMap<>();
  private final Map<String, NodeResult> nodeResults = new ConcurrentHashMap<>();
  private final Map<String, List<NodeResult>> scatterResults = new ConcurrentHashMap<>();
  private final Map<String, String> prerequisites;
  private final long startTime;

  public ExecutionContext(String sessionId) {
    this.sessionId = sessionId;
    this.prerequisites = Map.of();
    this.startTime = System.currentTimeMillis();
  }

  public ExecutionContext(String sessionId, Map<String, NodeResult> seeds) {
    this(sessionId, seeds, Map.of());
  }

  public ExecutionContext(
      String sessionId, Map<String, NodeResult> seeds, Map<String, String> prerequisites) {
    this.sessionId = sessionId;
    this.prerequisites = prerequisites != null ? Map.copyOf(prerequisites) : Map.of();
    this.startTime = System.currentTimeMillis();
    if (seeds != null) nodeResults.putAll(seeds);
  }

  public String sessionId() {
    return sessionId;
  }

  public long startTime() {
    return startTime;
  }

  // ── node status ──────────────────────────────────────────────────────────

  public void setStatus(String nodeId, NodeStatus status) {
    nodeStatuses.put(nodeId, status);
  }

  public NodeStatus getStatus(String nodeId) {
    return nodeStatuses.getOrDefault(nodeId, NodeStatus.PENDING);
  }

  // ── node results ─────────────────────────────────────────────────────────

  public void setResult(String nodeId, NodeResult result) {
    nodeResults.put(nodeId, result);
  }

  public NodeResult getResult(String nodeId) {
    return nodeResults.get(nodeId);
  }

  public Map<String, NodeStatus> allStatuses() {
    return Map.copyOf(nodeStatuses);
  }

  public Map<String, NodeResult> allResults() {
    return Map.copyOf(nodeResults);
  }

  // ── scatter results (fan-out) ─────────────────────────────────────────────

  public void setScatterResults(String nodeId, List<NodeResult> results) {
    scatterResults.put(nodeId, List.copyOf(results));
  }

  public List<NodeResult> getScatterResults(String nodeId) {
    return scatterResults.get(nodeId);
  }

  public boolean hasScatterResults(String nodeId) {
    return scatterResults.containsKey(nodeId);
  }

  // ── prerequisites ─────────────────────────────────────────────────────────

  public String getPrerequisite(String name) {
    return prerequisites.get(name);
  }

  public Map<String, String> allPrerequisites() {
    return prerequisites;
  }

  // ── timing ───────────────────────────────────────────────────────────────

  public long elapsedMs() {
    return System.currentTimeMillis() - startTime;
  }
}
