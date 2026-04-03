package com.returney.flow.schema;

/**
 * 프롬프트의 출력 정의.
 *
 * @param type 출력 타입 ("json" | "text")
 * @param description 설명
 */
public record OutputDefinition(String type, String description) {

  public OutputDefinition {
    if (type == null) type = "text";
  }

  public static OutputDefinition text(String description) {
    return new OutputDefinition("text", description);
  }

  public static OutputDefinition json(String description) {
    return new OutputDefinition("json", description);
  }
}
