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
