package com.returney.flow.port;

import java.util.List;
import java.util.Map;

/**
 * 서버 커스텀 노드 실행 포트.
 *
 * <p>SCATTER / GATHER / TRANSFORM 노드를 호출자가 구현한다. flow-core 엔진은 이 인터페이스를 통해 호출한다.
 */
public interface ServerNodeExecutor {

  /** SCATTER: 입력 맵을 받아 팬아웃할 문자열 청크 목록을 반환한다. */
  List<String> scatter(String nodeId, Map<String, String> inputs);

  /** GATHER: 청크 목록을 받아 수렴된 단일 문자열을 반환한다. */
  String gather(String nodeId, List<String> chunks);

  /** TRANSFORM: 입력 맵을 받아 변환된 단일 문자열을 반환한다. */
  String transform(String nodeId, Map<String, String> inputs);

  /** 이 실행기가 해당 nodeId를 처리할 수 있는지 반환한다. */
  boolean supports(String nodeId);
}
