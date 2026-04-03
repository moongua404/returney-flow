package com.returney.flow.engine;

/**
 * 플로우 실행 설정.
 *
 * @param model 모델 오버라이드 (null이면 기본 모델 사용)
 * @param thinkingBudget thinking 토큰 예산 (-1: 기본, 0: OFF, 양수: 지정)
 */
public record ExecutionConfig(String model, int thinkingBudget) {

  public static ExecutionConfig defaults() {
    return new ExecutionConfig(null, -1);
  }

  public static ExecutionConfig withModel(String model) {
    return new ExecutionConfig(model, -1);
  }
}
