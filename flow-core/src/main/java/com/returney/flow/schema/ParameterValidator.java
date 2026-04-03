package com.returney.flow.schema;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 입력 파라미터 값의 형식을 검증한다. */
public class ParameterValidator {

  /**
   * 값이 InputParameter의 format 제약을 만족하는지 검증한다.
   *
   * <p>format 해석 규칙:
   * <ul>
   *   <li>null → 항상 통과</li>
   *   <li>"a|b|c" → 값이 a, b, c 중 하나인지 (enum)</li>
   *   <li>"/^pattern$/" → 정규식 매칭</li>
   * </ul>
   */
  public ValidationResult validate(String value, InputParameter param) {
    if (param.required() && (value == null || value.isBlank())) {
      return ValidationResult.fail("Required parameter '" + param.name() + "' is missing or blank");
    }

    if (value == null || value.isBlank()) {
      return ValidationResult.OK; // optional이고 값 없음
    }

    String format = param.format();
    if (format == null || format.isBlank()) {
      return ValidationResult.OK;
    }

    // 정규식 패턴: /pattern/
    if (format.startsWith("/") && format.endsWith("/") && format.length() > 2) {
      String regex = format.substring(1, format.length() - 1);
      try {
        if (!Pattern.matches(regex, value)) {
          return ValidationResult.fail(
              "Parameter '" + param.name() + "' does not match pattern: " + format);
        }
      } catch (PatternSyntaxException e) {
        return ValidationResult.fail(
            "Invalid regex pattern for '" + param.name() + "': " + e.getMessage());
      }
      return ValidationResult.OK;
    }

    // enum 패턴: a|b|c
    if (format.contains("|")) {
      Set<String> allowed = Set.of(format.split("\\|"));
      if (!allowed.contains(value)) {
        return ValidationResult.fail(
            "Parameter '"
                + param.name()
                + "' must be one of ["
                + format
                + "], got: "
                + value);
      }
      return ValidationResult.OK;
    }

    // 단일 값 매칭
    if (!format.equals(value)) {
      return ValidationResult.fail(
          "Parameter '" + param.name() + "' must be '" + format + "', got: " + value);
    }

    return ValidationResult.OK;
  }
}
