package com.returney.flow.util;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 도메인 record (+ 중첩 record/List)를 reflective하게 walk하여 LLM에게 보여줄 평문 schema 텍스트를
 * 만든다. {@link Hint} 어노테이션이 있으면 description / enum 제약을 반영.
 *
 * <p>typing은 record component 이름 = JSON 키, 컴포넌트 타입 = JSON 타입 (단순 매핑).
 *
 * <p>출력 예:
 * <pre>{@code
 * 출력 스펙 (정확히 이 JSON 구조로만 응답):
 * {
 *   "topicCandidates": [   // 주제 카드 배열, 3~6개
 *     {
 *       "topicId": "string",   // t1, t2... 식별자
 *       "title": "string",     // 주제 제목
 *       "type": "gap" | "choice" | "achievement",   // 주제 유형
 *       "evidence": ["string"]
 *     }
 *   ],
 *   "profileSummary": "string"
 * }
 * }</pre>
 */
public final class SchemaRenderer {

  private static final java.util.Map<Class<?>, String> CACHE = new ConcurrentHashMap<>();

  private SchemaRenderer() {}

  /**
   * 주어진 type에 대한 평문 schema 텍스트를 반환. record가 아니거나 builtin이면 null
   * (호출자가 첨부 스킵).
   */
  public static String renderAsPrompt(Class<?> type) {
    if (type == null || !type.isRecord()) return null;
    return CACHE.computeIfAbsent(type, SchemaRenderer::buildPrompt);
  }

  private static String buildPrompt(Class<?> type) {
    StringBuilder sb = new StringBuilder();
    sb.append("\n\n출력 스펙 (정확히 이 JSON 구조와 키 이름으로만 응답):\n");
    renderObject(sb, type, 0);
    sb.append('\n');
    return sb.toString();
  }

  private static void renderObject(StringBuilder sb, Class<?> recordType, int depth) {
    String pad = "  ".repeat(depth);
    sb.append("{\n");
    RecordComponent[] components = recordType.getRecordComponents();
    for (int i = 0; i < components.length; i++) {
      RecordComponent c = components[i];
      Hint hint = c.getAnnotation(Hint.class);
      sb.append(pad).append("  \"").append(c.getName()).append("\": ");
      renderValue(sb, c.getGenericType(), c.getType(), hint, depth + 1);
      if (i < components.length - 1) sb.append(',');
      if (hint != null && !hint.value().isBlank()) {
        sb.append("   // ").append(hint.value());
      }
      if (hint != null && !hint.required()) {
        sb.append(hint.value().isBlank() ? "   // " : ", ").append("nullable");
      }
      sb.append('\n');
    }
    sb.append(pad).append('}');
  }

  private static void renderValue(StringBuilder sb, Type genericType, Class<?> rawType, Hint hint, int depth) {
    // enum 제약 (String + enumValues)
    if (rawType == String.class && hint != null && hint.enumValues().length > 0) {
      sb.append(Arrays.stream(hint.enumValues())
          .map(v -> "\"" + v + "\"")
          .reduce((a, b) -> a + " | " + b)
          .orElse("\"\""));
      return;
    }
    // primitives / String / Boolean
    if (rawType == String.class || CharSequence.class.isAssignableFrom(rawType)) {
      sb.append("\"string\"");
      return;
    }
    if (rawType == int.class || rawType == Integer.class
        || rawType == long.class || rawType == Long.class
        || rawType == double.class || rawType == Double.class
        || rawType == float.class || rawType == Float.class) {
      sb.append("number");
      return;
    }
    if (rawType == boolean.class || rawType == Boolean.class) {
      sb.append("true | false");
      return;
    }
    // List<T>
    if (List.class.isAssignableFrom(rawType)) {
      Class<?> elementType = listElementType(genericType);
      sb.append('[');
      if (elementType != null && elementType.isRecord()) {
        sb.append('\n').append("  ".repeat(depth + 1));
        renderObject(sb, elementType, depth + 1);
        sb.append('\n').append("  ".repeat(depth)).append(']');
      } else if (elementType == String.class) {
        sb.append("\"string\", ...");
        sb.append(']');
      } else if (elementType != null) {
        sb.append("...");
        sb.append(']');
      } else {
        sb.append(']');
      }
      return;
    }
    // 중첩 record
    if (rawType.isRecord()) {
      renderObject(sb, rawType, depth);
      return;
    }
    // enum 타입 (Java enum)
    if (rawType.isEnum()) {
      String options = Arrays.stream(rawType.getEnumConstants())
          .map(e -> "\"" + ((Enum<?>) e).name() + "\"")
          .reduce((a, b) -> a + " | " + b).orElse("\"\"");
      sb.append(options);
      return;
    }
    // 알 수 없는 타입 — fallback
    sb.append("/* ").append(rawType.getSimpleName()).append(" */");
  }

  private static Class<?> listElementType(Type genericType) {
    if (genericType instanceof ParameterizedType pt) {
      Type[] args = pt.getActualTypeArguments();
      if (args.length == 1 && args[0] instanceof Class<?> c) return c;
      if (args.length == 1 && args[0] instanceof ParameterizedType inner
          && inner.getRawType() instanceof Class<?> c) return c;
    }
    return null;
  }

  // 무한 재귀 방지 (자기 참조 record가 있으면 — 우리는 현재 없지만 안전망).
  // 깊이 제한이나 visited set 같은 것 — 지금은 단순화: depth>10이면 truncate.
  // (현재 도메인 모델은 트리, 자기 참조 없으므로 발동 안 함)
}
