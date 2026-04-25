package com.returney.flow.port;

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

  /** {@code System.getenv("<NAME>_API_KEY")} 기반 기본 구현. */
  static ApiKeySupplier fromEnv() {
    return name -> System.getenv(name.toUpperCase().replace('-', '_') + "_API_KEY");
  }
}
