package com.returney.flow.spi;

/**
 * 노드 실행 결과의 저장/조회 추상화.
 *
 * <p>호출자가 파일 시스템, DB 등 원하는 저장소로 구현한다.
 */
public interface DataStore {

  /**
   * 캐시된 노드 출력을 로드한다.
   *
   * @return 캐시된 출력 문자열. 없으면 null.
   */
  String load(String sessionId, String nodeId);

  /** 노드 실행 결과를 저장한다. */
  void save(String sessionId, String nodeId, String output);

  /** 캐시된 출력이 존재하는지 확인한다. */
  boolean exists(String sessionId, String nodeId);

  /**
   * 저장 시점의 프롬프트 해시를 반환한다 (캐시 무효화 판단용).
   *
   * @return 해시 문자열. 없으면 null.
   */
  String getPromptHash(String sessionId, String nodeId);
}
