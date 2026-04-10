package com.returney.flow.spi;

import java.util.Map;

/**
 * 프롬프트 템플릿 렌더링 추상화.
 *
 * <p>호출자가 YAML 기반 PromptLoader 등으로 구현한다.
 */
public interface PromptRenderer {

  /**
   * 템플릿에 변수를 치환하여 최종 프롬프트를 생성한다.
   *
   * @param action 프롬프트 액션명 (예: "profile_generation")
   * @param variables 치환할 변수 맵
   * @return 렌더링된 프롬프트 텍스트
   */
  String render(String action, Map<String, String> variables);

  /**
   * 액션에 정의된 모델명을 반환한다. 없으면 null.
   */
  default String getModel(String action) {
    return null;
  }

  /**
   * 액션에 정의된 thinking budget을 반환한다.
   */
  default int getThinkingBudget(String action) {
    return -1;
  }
}
