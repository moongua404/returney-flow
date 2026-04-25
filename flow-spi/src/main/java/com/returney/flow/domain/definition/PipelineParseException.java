package com.returney.flow.domain.definition;

/** 파이프라인 YAML 파싱 또는 검증 실패 시 발생하는 예외. */
public class PipelineParseException extends RuntimeException {

  public PipelineParseException(String message) {
    super(message);
  }

  public PipelineParseException(String message, Throwable cause) {
    super(message, cause);
  }
}
