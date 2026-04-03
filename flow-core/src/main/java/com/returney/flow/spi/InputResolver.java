package com.returney.flow.spi;

/**
 * 노드 입력 변수의 실제 값을 해석하는 추상화.
 *
 * <p>source가 다른 노드 ID인 경우 DataStore에서 출력을 로드하고, "session.purpose"인 경우 DB에서
 * 세션 데이터를 조회하는 등의 로직을 호출자가 구현한다.
 */
public interface InputResolver {

  /**
   * 노드의 입력 변수를 실제 값으로 해석한다.
   *
   * @param sessionId 세션 ID
   * @param nodeId 현재 실행 중인 노드 ID
   * @param paramName 파라미터 이름 (예: "retroContext")
   * @param sourceSpec 소스 지정자 (예: "file_analysis", "session.purpose")
   * @return 해석된 문자열 값
   */
  String resolve(String sessionId, String nodeId, String paramName, String sourceSpec);
}
