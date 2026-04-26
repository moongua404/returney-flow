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
   * {@code System.getenv("<NAME>_API_KEY")} 기반 기본 구현.
   *
   * <p>{@code toUpperCase()}는 기본 로케일 의존(Turkish locale에서 "i" → "İ"). 환경변수
   * 키는 ASCII만 다루므로 {@link Locale#ROOT} 명시.
   */
  static ApiKeySupplier fromEnv() {
    return name -> System.getenv(name.toUpperCase(Locale.ROOT).replace('-', '_') + "_API_KEY");
  }
}
