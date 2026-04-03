package com.returney.flow.engine;

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
  private final long startTime;

  public ExecutionContext(String sessionId) {
    this.sessionId = sessionId;
    this.startTime = System.currentTimeMillis();
  }

  public String sessionId() {
    return sessionId;
  }

  public long startTime() {
    return startTime;
  }

  public void setStatus(String nodeId, NodeStatus status) {
    nodeStatuses.put(nodeId, status);
  }

  public NodeStatus getStatus(String nodeId) {
    return nodeStatuses.getOrDefault(nodeId, NodeStatus.PENDING);
  }

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

  public long elapsedMs() {
    return System.currentTimeMillis() - startTime;
  }
}
