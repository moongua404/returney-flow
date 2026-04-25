package com.returney.flow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.returney.flow.adapter.parser.ProvidersConfig;
import com.returney.flow.adapter.parser.ProvidersYamlParser;
import com.returney.flow.domain.llm.LlmCallException;
import com.returney.flow.domain.llm.LlmRawResponse;
import com.returney.flow.domain.llm.LlmRequest;
import com.returney.flow.port.ApiKeySupplier;
import com.returney.flow.port.LlmExecutor;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InternalLlmRouterTest {

  private static final ProvidersConfig CONFIG = ProvidersYamlParser.loadFromClasspath();

  @Test
  void 키_없으면_프로바이더_미등록_라우팅_시_명확한_에러() {
    InternalLlmRouter router = InternalLlmRouter.from(CONFIG, name -> null);

    assertThatThrownBy(() ->
            router.execute(LlmRequest.single("hello", "claude-sonnet-4-6", 0)))
        .isInstanceOf(LlmCallException.class)
        .hasMessageContaining("missing API key");
  }

  @Test
  void 라우팅_매치_없으면_명확한_에러() {
    InternalLlmRouter router = InternalLlmRouter.from(CONFIG, fakeKeysFor("gemini"));

    assertThatThrownBy(() ->
            router.execute(LlmRequest.single("x", "weird-model-xyz", 0)))
        .isInstanceOf(LlmCallException.class)
        .hasMessageContaining("No provider matches");
  }

  @Test
  void model이_비면_default로_보강되어_라우팅() throws Exception {
    AtomicReference<LlmRequest> captured = new AtomicReference<>();

    // gemini 프로바이더 자리에 직접 stub LlmExecutor를 끼울 수 없으므로,
    // default("gemini-2.5-flash") 모델이 gemini로 라우팅된다는 사실만 확인.
    // 실제 호출은 키 누락으로 실패하지만, 라우팅 단계까진 통과해야 함.
    InternalLlmRouter routerNoGemini = InternalLlmRouter.from(CONFIG, name -> null);

    assertThatThrownBy(() ->
            routerNoGemini.execute(LlmRequest.single("hi", null, 0)))
        .isInstanceOf(LlmCallException.class)
        .hasMessageContaining("Provider gemini")
        .hasMessageContaining("model=gemini-2.5-flash");
  }

  @Test
  void ApiKeySupplier_fromEnv_기본_구현() {
    ApiKeySupplier env = ApiKeySupplier.fromEnv();
    // 환경에 ANTHROPIC_API_KEY 또는 GEMINI_API_KEY가 설정되어 있을 수 있고
    // 없을 수도 있으므로 비-null 가정 없이 호출만 검증.
    String anthropicKey = env.get("anthropic");
    String none = env.get("nonexistent-provider-xyz");
    assertThat(none).isNull();
    // anthropicKey는 환경 따라 null/값 모두 가능 — 호출 자체에 대한 stack trace 없이 끝나는지만 확인
    assertThat(anthropicKey == null || !anthropicKey.isEmpty()).isTrue();
  }

  /** 지정된 프로바이더 이름들에 대해서만 가짜 키를 반환. */
  private static ApiKeySupplier fakeKeysFor(String... providerNames) {
    Map<String, String> keys = Map.of();
    keys = new java.util.HashMap<>();
    for (String name : providerNames) keys.put(name, "fake-key-" + name);
    final Map<String, String> finalKeys = Map.copyOf(keys);
    return finalKeys::get;
  }
}
