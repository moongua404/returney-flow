package com.returney.flow.port;

import com.returney.flow.domain.llm.LlmCallContext;
import com.returney.flow.domain.llm.LlmCallException;
import com.returney.flow.domain.llm.LlmRawResponse;
import com.returney.flow.domain.llm.LlmRequest;

/**
 * LLM API 호출 추상화.
 *
 * <p>라이브러리는 어떤 LLM 프로바이더를 사용하는지 모른다. 호출자가 이 인터페이스를 구현하여 주입한다.
 *
 * <p>호출당 메타(sessionId, action, listener 등)는 {@link LlmCallContext}로 인자 전달.
 * ThreadLocal 사용 안 함 — fan-out된 자식 스레드에서도 안전.
 */
public interface LlmExecutor {

  /**
   * 구조화된 요청을 LLM에 전송한다.
   *
   * <p>단일 프롬프트, 대화 모드, 멀티모달 모두 이 메서드를 통해 처리한다.
   *
   * @param request LLM 호출 명세
   * @param context 호출 컨텍스트 (sessionId/action/variables/listener). 메타 불필요 시
   *                {@link LlmCallContext#empty()} 전달.
   * @return LLM 원시 응답 (텍스트 + 토큰 수)
   */
  LlmRawResponse execute(LlmRequest request, LlmCallContext context) throws LlmCallException;
}
