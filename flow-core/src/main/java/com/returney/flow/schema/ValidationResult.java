package com.returney.flow.schema;

/**
 * 파라미터 검증 결과.
 *
 * @param valid 유효 여부
 * @param message 실패 사유 (valid=true이면 null)
 */
public record ValidationResult(boolean valid, String message) {

  public static final ValidationResult OK = new ValidationResult(true, null);

  public static ValidationResult fail(String message) {
    return new ValidationResult(false, message);
  }
}
