package com.returney.flow.spi;

/**
 * LLM API 호출 추상화.
 *
 * <p>라이브러리는 어떤 LLM 프로바이더를 사용하는지 모른다. 호출자가 이 인터페이스를 구현하여 주입한다.
 */
public interface LlmExecutor {

  /**
   * 다음 execute() 호출의 세션 ID를 설정한다.
   *
   * @param sessionId 세션 ID (로그 기록용)
   */
  default void setSessionId(java.util.UUID sessionId) {}

  /**
   * 다음 execute() 호출의 컨텍스트를 설정한다. FlowExecutor가 노드 실행 전에 호출한다.
   *
   * @param action 프롬프트 액션명 (예: "profile_generation")
   * @param variables 치환된 변수 맵 (로그/캡처용)
   */
  default void setContext(String action, java.util.Map<String, String> variables) {}

  /**
   * 렌더링된 프롬프트를 LLM에 전송하고 응답 텍스트를 반환한다.
   *
   * @param renderedPrompt 변수 치환이 완료된 프롬프트 텍스트
   * @param model 사용할 모델 이름 (예: "gemini-2.5-flash")
   * @param thinkingBudget thinking 토큰 예산 (-1: 기본, 0: OFF, 양수: 지정)
   * @return LLM 응답 텍스트
   */
  String execute(String renderedPrompt, String model, int thinkingBudget);

  /**
   * 구조화된 요청을 LLM에 전송한다.
   *
   * <p>단일 프롬프트 요청은 기존 {@link #execute(String, String, int)}로 위임한다.
   * 대화 모드(멀티턴 캐싱 등)는 구현체에서 오버라이드한다.
   *
   * @param request LLM 호출 명세
   * @return LLM 응답 텍스트
   */
  default String execute(LlmRequest request) {
    if (!request.isConversation()) {
      return execute(request.singlePrompt(), request.model(), request.thinkingBudget());
    }
    throw new UnsupportedOperationException("Conversation mode not supported by this executor");
  }

  /**
   * 텍스트 프롬프트 + 바이너리 콘텐츠를 함께 LLM에 전송한다 (multimodal).
   *
   * @param textPrompt 텍스트 프롬프트
   * @param binaryContent 바이너리 콘텐츠 (PDF 등)
   * @param mimeType MIME 타입 (예: "application/pdf")
   * @param model 사용할 모델 이름
   * @param thinkingBudget thinking 토큰 예산
   * @return LLM 응답 텍스트
   */
  default String executeMultimodal(
      String textPrompt, byte[] binaryContent, String mimeType,
      String model, int thinkingBudget) {
    throw new UnsupportedOperationException("Multimodal not supported by this executor");
  }
}
