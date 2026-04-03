package com.returney.flow.schema;

import java.util.Map;
import java.util.Objects;

/**
 * 프롬프트 YAML 파싱 결과.
 *
 * @param action 액션명
 * @param model 모델명
 * @param thinking thinking 모드 활성화 여부
 * @param inputs 입력 파라미터 맵 (파라미터명 → 정의)
 * @param output 출력 정의 (nullable)
 * @param promptTemplate 프롬프트 템플릿 원문
 */
public record PromptDefinition(
    String action,
    String model,
    boolean thinking,
    Map<String, InputParameter> inputs,
    OutputDefinition output,
    String promptTemplate) {

  public PromptDefinition {
    Objects.requireNonNull(action, "action must not be null");
    if (inputs == null) inputs = Map.of();
    inputs = Map.copyOf(inputs);
  }
}
