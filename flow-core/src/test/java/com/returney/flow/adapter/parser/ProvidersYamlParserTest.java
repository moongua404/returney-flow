package com.returney.flow.adapter.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProvidersYamlParserTest {

  @Test
  void 클래스패스_로드() {
    ProvidersConfig config = ProvidersYamlParser.loadFromClasspath();

    assertThat(config.providers()).containsKeys("gemini", "anthropic", "openai", "openai-reasoning");
    assertThat(config.defaultModel()).isEqualTo("gemini-2.5-flash");
    assertThat(config.routing()).isNotEmpty();
  }

  @Test
  void prefix_매칭_라우팅() {
    ProvidersConfig config = ProvidersYamlParser.loadFromClasspath();

    assertThat(config.resolveProvider("gemini-2.5-flash")).isEqualTo("gemini");
    assertThat(config.resolveProvider("claude-sonnet-4-6")).isEqualTo("anthropic");
    assertThat(config.resolveProvider("gpt-4.1-mini")).isEqualTo("openai");
    assertThat(config.resolveProvider("gpt-5-mini")).isEqualTo("openai-reasoning");
    assertThat(config.resolveProvider("o4-mini")).isEqualTo("openai-reasoning");
  }

  @Test
  void 매칭_없으면_null() {
    ProvidersConfig config = ProvidersYamlParser.loadFromClasspath();

    assertThat(config.resolveProvider("unknown-model-xyz")).isNull();
    assertThat(config.resolveProvider(null)).isNull();
    assertThat(config.resolveProvider("")).isNull();
  }

  @Test
  void apiKeyName_지정_및_default() {
    ProvidersConfig config = ProvidersYamlParser.loadFromClasspath();

    assertThat(config.providers().get("openai-reasoning").apiKeyName()).isEqualTo("openai");
    assertThat(config.providers().get("anthropic").apiKeyName()).isEqualTo("anthropic");
  }

  @Test
  void 라우팅이_미등록_프로바이더를_가리키면_파싱_실패() {
    String yaml =
        """
        providers:
          gemini:
            type: gemini
            baseUrl: https://example.com
        routing:
          - prefix: claude-
            provider: anthropic
        default: gemini-2.5-flash
        """;

    assertThatThrownBy(() -> ProvidersYamlParser.parse(yaml))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown provider: anthropic");
  }

  @Test
  void retry_섹션_없으면_DEFAULT_적용() {
    String yaml =
        """
        providers:
          gemini:
            type: gemini
            baseUrl: https://example.com
        routing:
          - prefix: "gemini-"
            provider: gemini
        default: gemini-2.5-flash
        """;

    ProvidersConfig config = ProvidersYamlParser.parse(yaml);
    assertThat(config.retryPolicy()).isEqualTo(ProvidersConfig.RetryPolicy.DEFAULT);
  }

  @Test
  void retry_섹션_파싱() {
    ProvidersConfig config = ProvidersYamlParser.loadFromClasspath();

    ProvidersConfig.RetryPolicy r = config.retryPolicy();
    assertThat(r.maxAttempts()).isEqualTo(3);
    assertThat(r.initialDelayMs()).isEqualTo(500L);
    assertThat(r.maxDelayMs()).isEqualTo(10_000L);
    assertThat(r.backoffMultiplier()).isEqualTo(2.0);
    assertThat(r.jitter()).isEqualTo(0.2);
  }

  @Test
  void defaults_섹션_없으면_HttpDefaults_DEFAULT() {
    String yaml =
        """
        providers:
          gemini:
            type: gemini
            baseUrl: https://example.com
        routing:
          - prefix: "gemini-"
            provider: gemini
        default: gemini-2.5-flash
        """;

    ProvidersConfig config = ProvidersYamlParser.parse(yaml);
    assertThat(config.defaults()).isEqualTo(ProvidersConfig.HttpDefaults.DEFAULT);
  }

  @Test
  void defaults_섹션_파싱() {
    String yaml =
        """
        providers:
          gemini:
            type: gemini
            baseUrl: https://example.com
        routing:
          - prefix: "gemini-"
            provider: gemini
        default: gemini-2.5-flash
        defaults:
          connectTimeoutSec: 5
          requestTimeoutSec: 60
        """;

    ProvidersConfig config = ProvidersYamlParser.parse(yaml);
    assertThat(config.defaults().connectTimeoutSec()).isEqualTo(5);
    assertThat(config.defaults().requestTimeoutSec()).isEqualTo(60);
  }

  @Test
  void HttpDefaults_부정값_거부() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ProvidersConfig.HttpDefaults(0, 60))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ProvidersConfig.HttpDefaults(10, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void retry_부분_명시는_나머지_DEFAULT_사용() {
    String yaml =
        """
        providers:
          gemini:
            type: gemini
            baseUrl: https://example.com
        routing:
          - prefix: "gemini-"
            provider: gemini
        default: gemini-2.5-flash
        retry:
          maxAttempts: 5
        """;

    ProvidersConfig config = ProvidersYamlParser.parse(yaml);
    ProvidersConfig.RetryPolicy r = config.retryPolicy();
    assertThat(r.maxAttempts()).isEqualTo(5);
    // 나머지는 DEFAULT
    assertThat(r.initialDelayMs()).isEqualTo(ProvidersConfig.RetryPolicy.DEFAULT.initialDelayMs());
    assertThat(r.backoffMultiplier()).isEqualTo(ProvidersConfig.RetryPolicy.DEFAULT.backoffMultiplier());
  }

  @Test
  void default_누락_시_파싱_실패() {
    String yaml =
        """
        providers:
          gemini:
            type: gemini
            baseUrl: https://example.com
        """;

    assertThatThrownBy(() -> ProvidersYamlParser.parse(yaml))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing 'default'");
  }
}
