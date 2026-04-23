package com.returney.flow.port;

/** nodeId.fieldName 참조 해석 포트. Gradle 코드젠으로 생성된 구현체가 주입된다. */
@FunctionalInterface
public interface NodeOutputExtractor {
  String extract(String nodeId, String fieldName, String nodeOutput);
}
