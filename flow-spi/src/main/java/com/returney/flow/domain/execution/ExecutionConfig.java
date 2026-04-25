package com.returney.flow.domain.execution;

/**
 * 파이프라인 실행 설정.
 *
 * @param model 모델 오버라이드 (null이면 YAML 정의 모델 사용)
 * @param thinkingBudget thinking 토큰 예산 (-1: YAML 정의 값 사용, 0: OFF, 양수: 지정)
 */
public record ExecutionConfig(String model, int thinkingBudget) {

  private static final String FALLBACK_MODEL = "gemini-2.5-flash";

  public static ExecutionConfig defaults() {
    return new ExecutionConfig(null, -1);
  }

  public static ExecutionConfig withModel(String model) {
    return new ExecutionConfig(model, -1);
  }

  /** config 오버라이드 → yamlModel → 기본값 순으로 모델을 결정한다. */
  public String resolveModel(String yamlModel) {
    if (model != null) return model;
    if (yamlModel != null) return yamlModel;
    return FALLBACK_MODEL;
  }

  /** config 오버라이드 → yamlBudget 순으로 thinking budget을 결정한다. */
  public int resolveThinkingBudget(int yamlBudget) {
    return thinkingBudget >= 0 ? thinkingBudget : Math.max(0, yamlBudget);
  }
}
