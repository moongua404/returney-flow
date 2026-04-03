package com.returney.flow.schema;

/**
 * 프롬프트의 입력 파라미터 정의.
 *
 * @param name 파라미터 이름 (예: "retroContext")
 * @param type 타입 ("string" 기본)
 * @param description 설명
 * @param source 값의 출처 (노드 ID 또는 "session.purpose" 등)
 * @param format 형식 제약 (nullable — "a|b|c" enum 또는 "/regex/" 패턴)
 * @param required 필수 여부 (기본 true)
 */
public record InputParameter(
    String name,
    String type,
    String description,
    String source,
    String format,
    boolean required) {

  public InputParameter {
    if (type == null) type = "string";
    if (required == false && format == null) {
      // 기본값 유지
    }
  }

  public static InputParameter of(String name, String source) {
    return new InputParameter(name, "string", null, source, null, true);
  }
}
