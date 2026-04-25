package com.returney.flow.port;

import com.returney.flow.domain.llm.LlmCallException;
import com.returney.flow.domain.llm.LlmRawResponse;
import com.returney.flow.domain.llm.LlmRequest;
import java.util.Map;
import java.util.UUID;

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
  default void setSessionId(UUID sessionId) {}

  /**
   * 다음 execute() 호출의 컨텍스트를 설정한다. PipelineExecutor가 노드 실행 전에 호출한다.
   *
   * @param action 프롬프트 액션명 (예: "profile_generation")
   * @param variables 치환된 변수 맵 (로그/캡처용)
   */
  default void setContext(String action, Map<String, String> variables) {}

  /**
   * 구조화된 요청을 LLM에 전송한다.
   *
   * <p>단일 프롬프트, 대화 모드, 멀티모달 모두 이 메서드를 통해 처리한다.
   *
   * @param request LLM 호출 명세
   * @return LLM 원시 응답 (텍스트 + 토큰 수)
   */
  LlmRawResponse execute(LlmRequest request) throws LlmCallException;
}
