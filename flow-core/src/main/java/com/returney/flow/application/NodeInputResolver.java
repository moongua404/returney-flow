package com.returney.flow.application;

import com.google.gson.Gson;
import com.returney.flow.domain.definition.PipelineNode;
import com.returney.flow.domain.execution.NodeResult;
import com.returney.flow.port.NodeOutputExtractor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 노드 입력 변수 해석기.
 *
 * <p>소스 스펙을 실행 컨텍스트에서 실제 값으로 변환한다.
 * <ul>
 *   <li>{@code "Prerequisites.name"} → 객체 전체: String as-is, UUID/bool/Number→toString, Object→JSON
 *   <li>{@code "Prerequisites.name.field"} → 객체 필드 접근: reflection accessor → toString()
 *   <li>{@code "nodeId.fieldName"} → {@link NodeOutputExtractor} 위임
 *   <li>{@code "nodeId"} → 노드 결과 output 그대로 반환
 * </ul>
 */
public class NodeInputResolver {

  private static final String PREREQ_PREFIX = "Prerequisites.";
  private static final Gson GSON = new Gson();

  private final NodeOutputExtractor extractor;

  public NodeInputResolver(NodeOutputExtractor extractor) {
    this.extractor = extractor;
  }

  /**
   * 노드의 inputs 선언을 컨텍스트에서 해석해 변수 맵으로 반환한다 (프롬프트 주입용).
   *
   * <p>모든 값이 String으로 직렬화된다. LLM 노드 프롬프트 렌더링에 사용.
   *
   * @param node 실행할 노드
   * @param ctx 현재 실행 컨텍스트
   * @return 변수명 → String 값 맵 (null 값은 제외됨)
   */
  public Map<String, String> resolve(PipelineNode node, ExecutionContext ctx) {
    Map<String, String> variables = new HashMap<>();
    for (Map.Entry<String, String> entry : node.inputs().entrySet()) {
      String value = resolveSource(entry.getValue(), ctx);
      if (value != null) variables.put(entry.getKey(), value);
    }
    return variables;
  }

  /**
   * 노드의 inputs 선언을 컨텍스트에서 해석해 객체 맵으로 반환한다 (서버 노드용).
   *
   * <p>모든 값은 {@code toString} 으로 통일된 문자열 — prerequisites 도 노드 결과도 같은 contract.
   * 서버 노드 구현체가 필요한 형태로 변환 (예: {@code UUID.fromString}).
   */
  public Map<String, Object> resolveTyped(PipelineNode node, ExecutionContext ctx) {
    Map<String, Object> variables = new HashMap<>();
    for (Map.Entry<String, String> entry : node.inputs().entrySet()) {
      Object value = resolveSourceTyped(entry.getValue(), ctx);
      if (value != null) variables.put(entry.getKey(), value);
    }
    return variables;
  }

  private String resolveSource(String sourceSpec, ExecutionContext ctx) {
    if (sourceSpec.startsWith(PREREQ_PREFIX)) {
      String rest = sourceSpec.substring(PREREQ_PREFIX.length());
      if (rest.contains(".")) {
        int dot = rest.indexOf('.');
        Object obj = ctx.getPrerequisite(rest.substring(0, dot));
        return accessField(obj, rest.substring(dot + 1));
      }
      return serializePrereq(ctx.getPrerequisite(rest));
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
    if (result == null) return null;
    if (result.typedOutput() != null) {
      return result.typedOutput().toString();
    }
    return result.output();
  }

  private Object resolveSourceTyped(String sourceSpec, ExecutionContext ctx) {
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
    if (result == null) return null;
    if (result.typedOutput() != null) {
      return result.typedOutput().toString();
    }
    return result.output();
  }

  private static String serializePrereq(Object value) {
    if (value == null) return null;
    if (value instanceof String s) return s;
    return value.toString();
  }

  /** 객체의 특정 필드를 reflection accessor로 접근해 toString() 반환. Gson fallback 포함. */
  private static String accessField(Object obj, String fieldName) {
    if (obj == null) return null;
    try {
      Object val = obj.getClass().getMethod(fieldName).invoke(obj);
      return val != null ? val.toString() : null;
    } catch (Exception e1) {
      try {
        com.google.gson.JsonObject json =
            GSON.fromJson(GSON.toJson(obj), com.google.gson.JsonObject.class);
        com.google.gson.JsonElement elem = json.get(fieldName);
        return elem != null && !elem.isJsonNull() ? elem.getAsString() : null;
      } catch (Exception e2) {
        return null;
      }
    }
  }
}
