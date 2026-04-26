package com.returney.flow.domain.llm;

import com.returney.flow.port.ExecutionListener;
import java.util.Map;
import java.util.UUID;

/**
 * 단일 LLM 호출에 부수되는 컨텍스트.
 *
 * <p>이전 버전은 {@code LlmExecutor}에 setSessionId/setContext/setLifecycle 메서드를
 * 두고 ThreadLocal로 전달했으나, fan-out된 자식 스레드에 ThreadLocal이 상속되지
 * 않아 sessionId 누락 문제가 있었다. 이 record로 명시적 인자 전달.
 *
 * @param sessionId 호출 시점의 세션 ID. 없으면 null.
 * @param action 프롬프트 액션명 (예: "profile_generation"). 없으면 null.
 * @param variables 치환된 변수 맵 (로그/캡처용). 없으면 빈 맵.
 * @param listener LLM 호출 결과 이벤트 리스너. 없으면 noop.
 */
public record LlmCallContext(
    UUID sessionId,
    String action,
    Map<String, String> variables,
    ExecutionListener listener) {

  public LlmCallContext {
    if (variables == null) variables = Map.of();
    if (listener == null) listener = ExecutionListener.noop();
  }

  /** 메타 정보 없는 빈 컨텍스트. 외부에서 LlmExecutor를 직접 호출할 때 기본값. */
  public static LlmCallContext empty() {
    return new LlmCallContext(null, null, Map.of(), ExecutionListener.noop());
  }
}
