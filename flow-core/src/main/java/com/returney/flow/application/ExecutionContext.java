package com.returney.flow.application;

import com.returney.flow.domain.execution.NodeResult;
import com.returney.flow.domain.execution.NodeStatus;
import java.util.HashMap;
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
  private final Map<String, Object> prerequisites;
  private final long startTime;

  public ExecutionContext(
      String sessionId, Map<String, NodeResult> seeds, Map<String, Object> prerequisites) {
    this.sessionId = sessionId;
    this.prerequisites = sanitizePrerequisites(prerequisites);
    this.startTime = System.currentTimeMillis();
    if (seeds != null) nodeResults.putAll(seeds);
  }

  /**
   * null value를 빈 문자열로 정상화한 immutable 복사본을 반환.
   *
   * <p>{@link Map#copyOf}가 null entry를 거부해 NPE를 던지는 문제 회피.
   * prompts에서 "값 없음"은 빈 문자열로 렌더되는 게 자연스럽다.
   */
  private static Map<String, Object> sanitizePrerequisites(Map<String, Object> input) {
    if (input == null || input.isEmpty()) return Map.of();
    Map<String, Object> sanitized = new HashMap<>(input.size());
    for (Map.Entry<String, Object> e : input.entrySet()) {
      if (e.getKey() == null) continue;
      sanitized.put(e.getKey(), e.getValue() != null ? e.getValue() : "");
    }
    return Map.copyOf(sanitized);
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

  public Object getPrerequisite(String name) {
    return prerequisites.get(name);
  }

  public Map<String, Object> allPrerequisites() {
    return prerequisites;
  }

  // ── timing ───────────────────────────────────────────────────────────────

  public long elapsedMs() {
    return System.currentTimeMillis() - startTime;
  }
}
