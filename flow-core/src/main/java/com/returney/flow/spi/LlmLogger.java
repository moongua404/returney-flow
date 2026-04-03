package com.returney.flow.spi;

/**
 * LLM 호출 결과 로깅 추상화.
 *
 * <p>호출자가 이 인터페이스를 구현하여 DB 로깅, 파일 로깅 등을 수행한다.
 */
public interface LlmLogger {

  /**
   * LLM 호출 결과를 기록한다.
   *
   * @param action 프롬프트 액션 이름 (예: "l3_components")
   * @param model 사용된 모델
   * @param inputTokens 입력 토큰 수 (추정)
   * @param outputTokens 출력 토큰 수 (추정)
   * @param latencyMs 호출 소요 시간 (ms)
   * @param success 성공 여부
   * @param error 실패 시 에러 메시지 (성공이면 null)
   */
  void log(String action, String model, int inputTokens, int outputTokens,
      long latencyMs, boolean success, String error);
}
