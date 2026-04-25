package com.returney.flow.application;

import com.returney.flow.domain.definition.PipelineNode;
import com.returney.flow.domain.execution.ExecutionContext;
import com.returney.flow.domain.execution.NodeResult;
import com.returney.flow.port.NodeOutputExtractor;
import java.util.HashMap;
import java.util.Map;

/**
 * 노드 입력 변수 해석기.
 *
 * <p>소스 스펙을 실행 컨텍스트에서 실제 값으로 변환한다.
 * <ul>
 *   <li>{@code "Prerequisites.fieldName"} → prerequisites 맵에서 조회
 *   <li>{@code "nodeId.fieldName"} → {@link NodeOutputExtractor} 위임
 *   <li>{@code "nodeId"} → 노드 결과 output 그대로 반환
 * </ul>
 */
public class NodeInputResolver {

  private static final String PREREQ_PREFIX = "Prerequisites.";

  private final NodeOutputExtractor extractor;

  public NodeInputResolver(NodeOutputExtractor extractor) {
    this.extractor = extractor;
  }

  /**
   * 노드의 inputs 선언을 컨텍스트에서 해석해 변수 맵으로 반환한다.
   *
   * @param node 실행할 노드
   * @param ctx 현재 실행 컨텍스트
   * @return 변수명 → 값 맵 (null 값은 제외됨)
   */
  public Map<String, String> resolve(PipelineNode node, ExecutionContext ctx) {
    Map<String, String> variables = new HashMap<>();
    for (Map.Entry<String, String> entry : node.inputs().entrySet()) {
      String value = resolveSource(entry.getValue(), ctx);
      if (value != null) variables.put(entry.getKey(), value);
    }
    return variables;
  }

  private String resolveSource(String sourceSpec, ExecutionContext ctx) {
    if (sourceSpec.startsWith(PREREQ_PREFIX)) {
      return ctx.getPrerequisite(sourceSpec.substring(PREREQ_PREFIX.length()));
    }
    if (sourceSpec.contains(".")) {
      int dot = sourceSpec.indexOf('.');
      String nodeId = sourceSpec.substring(0, dot);
      String fieldName = sourceSpec.substring(dot + 1);
      NodeResult raw = ctx.getResult(nodeId);
      if (raw == null || raw.output() == null) return null;
      return extractor.extract(nodeId, fieldName, raw.output());
    }
    NodeResult result = ctx.getResult(sourceSpec);
    return result != null ? result.output() : null;
  }
}
