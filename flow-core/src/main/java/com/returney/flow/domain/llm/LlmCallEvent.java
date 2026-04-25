package com.returney.flow.domain.llm;

/**
 * 단일 LLM 호출 결과 이벤트.
 *
 * <p>{@code ExecutionListener.onLlmCall(...)}을 통해 발행된다.
 * 소비자는 이 이벤트로 로깅/cost 계산/메트릭 적재 등을 처리할 수 있다.
 *
 * @param sessionId 호출 시점의 세션 ID (없으면 null)
 * @param action setContext로 전달된 액션명 (없으면 null)
 * @param request 실행된 요청
 * @param response 성공 시 응답, 실패 시 null
 * @param resolvedModel 실제 호출된 모델명 (failover 후엔 request.model()과 다를 수 있음)
 * @param latencyMs 호출 소요 시간 (ms)
 * @param success 성공 여부
 * @param error 실패 시 예외, 성공 시 null
 * @param attemptIndex 0이면 1차 시도, 1+이면 fallback 시도
 */
public record LlmCallEvent(
    String sessionId,
    String action,
    LlmRequest request,
    LlmRawResponse response,
    String resolvedModel,
    long latencyMs,
    boolean success,
    Throwable error,
    int attemptIndex) {

  public static LlmCallEvent success(
      String sessionId, String action, LlmRequest request, LlmRawResponse response,
      String resolvedModel, long latencyMs, int attemptIndex) {
    return new LlmCallEvent(
        sessionId, action, request, response, resolvedModel, latencyMs, true, null, attemptIndex);
  }

  public static LlmCallEvent failure(
      String sessionId, String action, LlmRequest request,
      String resolvedModel, long latencyMs, Throwable error, int attemptIndex) {
    return new LlmCallEvent(
        sessionId, action, request, null, resolvedModel, latencyMs, false, error, attemptIndex);
  }
}
