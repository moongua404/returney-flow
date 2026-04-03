package com.returney.flow.schema;

/** 플로우 YAML 파싱 또는 검증 실패 시 발생하는 예외. */
public class FlowParseException extends RuntimeException {

  public FlowParseException(String message) {
    super(message);
  }

  public FlowParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
