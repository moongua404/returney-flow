# flow-spi 명세 (목표 상태)

YAML 선언형 DAG LLM 파이프라인. 소비자는 yaml + codegen + 추상 클래스 구현으로 끝낸다. flow-core 내부(파서, 라우팅, 프로바이더 호출, rate limiting, 프롬프트 렌더링)는 외부에 노출되지 않는다.

> 이 문서는 리팩터링 **목표** 상태를 기술한다. 현 단계의 구현은 docs/SPI.md 이전 버전 또는 코드를 직접 참고하라.

---

## 모듈 구조

```
returney-flow/
├── flow-spi/        # 외부 의존 0. JDK only.
└── flow-core/       # flow-spi에 api 의존. snakeyaml/gson 등.
```

`flow-codegen`은 별도 standalone Gradle 프로젝트로 소비자의 `buildSrc`가 `includeBuild`로 가져간다.

---

## 소비자 워크플로우

1. 파이프라인 yaml 작성 (`pipeline-flow.yaml`, `prompts/*.yaml`, `providers.yaml`)
2. 빌드 시 codegen이 yaml 정합성을 검증하고 타입 안전한 베이스 클래스를 생성
3. 소비자는 생성된 `*PipelineBase`를 상속해 server-node 메서드 본문만 구현
4. 런타임: `pipeline.run(prerequisites, ...)` → core가 LLM 호출까지 전담

---

## flow-spi 노출 표면

총 9개 타입. 소비자가 직접 다루는 핵심은 3개(`Pipeline`, `*PipelineBase`(생성됨), `PipelineLifecycle`).

### 진입 인터페이스 (`com.returney.flow.api`)

```java
/** codegen이 만든 *PipelineBase가 구현. 소비자는 .run()만 호출. */
public interface Pipeline<P, R> {
    R run(P prerequisites, UUID sessionId, Set<String> nodeIds,
          ExecutionConfig config, PipelineLifecycle lifecycle);
}

/** 런타임 설정. 모델 오버라이드 등. */
public record ExecutionConfig(String modelOverride, ...) {
    public static ExecutionConfig defaults() { ... }
}

/** 라이프사이클 콜백. 로깅/메트릭/DB 적재용. 모든 메서드 default no-op. */
public interface PipelineLifecycle {
    default void onNodeStarted(String nodeId) {}
    default void onNodeCompleted(String nodeId, NodeResult result) {}
    default void onNodeFailed(String nodeId, String error) {}
    default void onLlmCall(String nodeId, LlmRequest req, LlmRawResponse resp) {}
    default void onFlowCompleted(ExecutionResult result) {}

    static PipelineLifecycle noop() { return new PipelineLifecycle() {}; }
}

/** 런타임 결과 (raw, untyped). codegen이 typed *Result로 변환. */
public record ExecutionResult(
    Map<String, NodeResult> nodeResults,
    Set<String> failedNodes,
    long durationMs) {}

public record NodeResult(String nodeId, NodeStatus status, String output,
                         String error, long durationMs) {}

public enum NodeStatus { SUCCESS, FAILED, SKIPPED }
```

### LLM 데이터 (`com.returney.flow.api.llm`)

`PipelineLifecycle.onLlmCall(...)` 콜백이 받는 데이터. 소비자가 직접 만들 일은 거의 없음.

```java
public record LlmRequest(
    String prompt, String systemPrompt, List<Message> messages,
    String model, int thinkingBudget, byte[] binaryContent,
    String mimeType, CacheConfig cacheConfig) {

    public record Message(String role, String content) {}
    public record CacheConfig(boolean enabled) {}

    public static LlmRequest single(String prompt, String model, int thinking) { ... }
    public static LlmRequest conversation(...) { ... }
    public static LlmRequest multimodal(...) { ... }
}

public record LlmRawResponse(String text, int promptTokens,
                             int completionTokens, int totalTokens,
                             int cacheCreationTokens, int cacheReadTokens) {}

public class LlmCallException extends Exception { ... }
public class LlmTransientException extends LlmCallException { ... }
public class LlmNetworkException extends LlmTransientException { ... }
public class LlmClientErrorException extends LlmCallException { ... }
```

---

## flow-core (비공개 — 참고용)

소비자가 import할 일이 없는 것들:

| 구성요소 | 위치 (예정) | 역할 |
|---|---|---|
| `PipelineExecutor`, `NodeExecutor`, `LlmNodeRunner`, `NodeInputResolver` | `application/` | DAG 실행 엔진 |
| `ExecutionContext` | `application/` | 런타임 가변 상태 |
| `PipelineYamlParser`, `RateLimitYamlParser`, `ProvidersYamlParser` | `adapter/parser/` | yaml 로더 |
| `ClasspathPromptRenderer` | `adapter/prompt/` | prompts/*.yaml 로드/렌더 |
| `SlidingWindowRateLimiter` | `adapter/common/` | RPM/TPM 한도 |
| `InternalLlmRouter` | `application/` | model → provider 매핑, failover |
| `ClaudeLlmExecutor`, `GeminiLlmExecutor`, `GptLlmExecutor`, `ReasoningLlmExecutor` | `adapter/provider/{anthropic,gemini,openai}/` | HTTP 호출 |
| `PipelineDefinition`, `PipelineNode`, `PipelineEdge`, `NodeType` | `application/definition/` | 파싱 결과 (내부) |
| `FlowCore` | `com.returney.flow` | 부트스트랩 팩토리 (codegen만 사용) |

---

## 소비자가 작성하는 yaml

### `pipeline-flow.yaml`
DAG 선언. 변경 없음. (직전 SPI.md 참조)

### `prompts/{action}.yaml`
프롬프트 템플릿. 변경 없음. **클래스패스에서만 로드** (외부 디렉터리/hot-reload 미지원).

### `providers.yaml` (신설, Phase 4a)
```yaml
providers:
  anthropic:
    apiKeyEnv: ANTHROPIC_API_KEY
    baseUrl: https://api.anthropic.com
  gemini:
    apiKeyEnv: GEMINI_API_KEY
  openai:
    apiKeyEnv: OPENAI_API_KEY

models:
  - name: claude-sonnet-4-6
    provider: anthropic
    fallback: [gemini-2.5-flash]
  - name: gemini-2.5-flash
    provider: gemini
  - name: gpt-4.1-mini
    provider: openai
  - name: o4-mini
    provider: openai
    reasoning: true
```

### `rate-limits.yaml`
모델별 RPM/TPM 한도. core 내부 사용.

---

## API 키 공급

기본: `providers.yaml`의 `apiKeyEnv` → `System.getenv(...)`.

오버라이드: `*PipelineBase`에 `protected String apiKey(String provider)` 메서드를 두어 Spring Vault, AWS Secrets Manager 등 외부 키 저장소와 연동 가능.

---

## codegen 산출물 (참고)

소비자 빌드 디렉터리에 다음이 생성됨:

| 파일 | 역할 |
|---|---|
| `*Prerequisites.java` | 외부 입력 record |
| `*Result.java` | 최종 출력 record (typed) |
| `*PipelineBase.java` | abstract — `Pipeline<P, R>` 구현체. 소비자가 상속 |

옛 산출물(`*FieldExtractor`, `*LlmMiddleware`)은 Phase 4b 이후 내부 흡수되어 사라진다.

---

## codegen 빌드타임 검증

빌드는 다음 정합성을 모두 확인하고, 실패 시 컴파일 단계에서 멈춘다.

- 노드 ID 중복
- `inputs:` 소스 노드 존재
- 순환 의존
- `Prerequisites.xxx` 참조 ↔ `prerequisites:` 목록
- `prompts/{action}.yaml` ↔ pipeline node `action` 1:1 매칭
- prompt 변수 `{{x}}` ↔ node `inputs:` 키 매칭
- `result.type` FQCN 클래스로더 존재 확인
- `inputs:` dot-access 필드 ↔ 업스트림 result record 필드 매칭
- prompt yaml의 `model` ↔ `providers.yaml` 모델 카탈로그 매칭
