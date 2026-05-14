package com.returney.flow.port;

import java.util.Locale;

/**
 * 프로바이더 API 키 공급자.
 *
 * <p>기본 구현은 환경변수 {@code <NAME>_API_KEY}를 읽는다.
 * Spring Vault, AWS Secrets Manager 등 외부 키 저장소를 쓰려면 람다로 오버라이드한다.
 */
@FunctionalInterface
public interface ApiKeySupplier {

  /**
   * @param providerName providers.yaml의 {@code apiKeyName} (예: "anthropic")
   * @return API 키 값. 키가 없으면 null/공백 (해당 프로바이더는 라우터에 등록되지 않음)
   */
  String get(String providerName);

  /**
   * {@code <NAME>_API_KEY} 기반 기본 구현. 환경변수 우선, 없으면 System property fallback.
   *
   * <p>로컬 개발에서 dotenv 같은 라이브러리는 보통 {@link System#setProperty}로 값을
   * 주입하므로, {@link System#getenv}만 봐선 못 찾는 케이스가 발생한다 (실제로 staging
   * 첫 가동 시 Gemini 키 미인식 사고가 있었음). 두 저장소 모두 조회하여 환경 차이를 흡수.
   *
   * <p>{@code toUpperCase()}는 기본 로케일 의존(Turkish locale에서 "i" → "İ"). 환경변수
   * 키는 ASCII만 다루므로 {@link Locale#ROOT} 명시.
   */
  static ApiKeySupplier fromEnv() {
    return name -> {
      String key = name.toUpperCase(Locale.ROOT).replace('-', '_') + "_API_KEY";
      String value = System.getenv(key);
      if (value == null || value.isBlank()) {
        value = System.getProperty(key);
      }
      return value;
    };
  }
}
