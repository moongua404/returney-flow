# returney-flow

[![CI](https://github.com/moongua404/returney-flow/actions/workflows/ci.yml/badge.svg)](https://github.com/moongua404/returney-flow/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

YAML 선언형 DAG LLM 파이프라인 라이브러리.

소비자는 yaml로 파이프라인을 선언하고 빌드 시 코드젠으로 타입 안전한 진입 클래스를 받는다.
LLM 호출(라우팅·재시도·rate limit·fallback)은 라이브러리가 전담한다.

---

## 흐름

```
*-flow.yaml ──┐
              │  build time
prompts/*.yaml┼──► flow-codegen ──► *PipelineBase.java (typed)
              │                     *PipelineResult.java
              │                     *PipelinePrerequisites.java
              ▼
        consumer ──► run() ──► flow-core (DAG executor)
                                  │
                                  ▼
                          InternalLlmRouter
                          ├─► ClaudeLlmExecutor
                          ├─► GeminiLlmExecutor
                          └─► GptLlmExecutor / ReasoningLlmExecutor
```

---

## 모듈

```
returney-flow/
├── flow-spi/        # JDK only. 소비자 import 대상 공개 계약 (24 클래스)
├── flow-core/       # DAG 엔진 + LLM 라우터 + 프로바이더 어댑터. flow-spi에 의존.
└── flow-codegen/    # Gradle build helper (standalone). yaml → Java 산출물.
```

의존 방향: **flow-spi ← flow-core ← consumer**. flow-codegen은 빌드 타임만.

---

## Quick Start

### 1. 의존성 (소비자 Gradle)

`settings.gradle`:
```gradle
includeBuild '../returney-flow'
```
`build.gradle`:
```gradle
implementation 'com.returney:flow-core:0.1.0-SNAPSHOT'
```
`buildSrc/settings.gradle` + `buildSrc/build.gradle`:
```gradle
// settings.gradle
includeBuild '../../returney-flow/flow-codegen'
// build.gradle
dependencies { implementation 'com.returney.flow:flow-codegen' }
```

### 2. yaml 작성

`src/main/resources/pipeline-flow.yaml`:
```yaml
name: my-pipeline
version: 1
prerequisites: [reportText]
nodes:
  - id: analyze
    type: llm
    action: analyze
    inputs: { text: Prerequisites.reportText }
    result: { type: java.lang.String }
```

`src/main/resources/prompts/analyze.yaml`:
```yaml
action: analyze
model: claude-sonnet-4-6
promptTemplate: |
  분석하세요: {{text}}
```

### 3. 코드젠 wiring

`build.gradle`:
```gradle
def generatedFlowDir = "${buildDir}/generated/sources/flow"
sourceSets.main.java.srcDirs += generatedFlowDir

tasks.register('generateFlowInterfaces') {
    inputs.file('src/main/resources/pipeline-flow.yaml')
    outputs.dir(generatedFlowDir)
    doLast {
        FlowInterfaceGenerator.generate(
            file('src/main/resources/pipeline-flow.yaml'),
            file(generatedFlowDir),
            'com.example.generated')
    }
}
compileJava.dependsOn 'generateFlowInterfaces'
```

### 4. 진입 코드

```java
@Component
public class MyPipeline extends MyPipelineBase {
    public MyPipeline(@Qualifier("flowExecutor") Executor exec) {
        super(exec);
    }
    // server-node 추상 메서드만 구현
}
```

호출:
```java
var prereqs = MyPipelinePrerequisites.from(Map.of("reportText", "..."));
var result = pipeline.run(prereqs, sessionId, MyPipelineBase.DEFINITION.nodeIds(),
    ExecutionConfig.defaults(), ExecutionListener.noop());
```

위 `pipeline.run(...)`은 코드젠이 만든 `*PipelineBase`의 wrapper다. 내부적으로
`PipelineRunner.run(definition, sessionId, nodeIds, config, seedResults, prerequisites)`
6-인자 시그니처로 위임하지만 소비자는 wrapper로 호출하면 충분.

API 키는 환경변수(`ANTHROPIC_API_KEY` / `GEMINI_API_KEY` / `OPENAI_API_KEY`)에서 자동 로드.

---

## Features

| 기능 | 설명 |
|---|---|
| DAG 실행 | Java Virtual Thread 병렬, fan-out(scatter) 지원 |
| LLM 라우팅 | 모델명 prefix → provider (yaml-driven) |
| Capability | thinking budget 자동 cap (모델별 미지원 시 0으로 강제) |
| Retry | exp backoff + jitter, transient 오류만 (`LlmTransientException`/`LlmNetworkException`) |
| Fallback | retry 모두 소진 시 fallback 모델 (yaml `fallback:` 체인) |
| Rate limit | 모델별 60초 슬라이딩 윈도우 (RPM/TPM) |
| Lifecycle hooks | `onNode*`/`onLlmCall`로 cost·logging·메트릭 |
| 빌드타임 검증 | 노드 ID 중복/순환/inputs/prompt action 매칭 |

---

## Provider 지원

| Provider | 모델 prefix | Executor |
|---|---|---|
| Anthropic | `claude-` | `ClaudeLlmExecutor` |
| Gemini | `gemini-` | `GeminiLlmExecutor` |
| OpenAI (chat) | `gpt-` (4.1/4o) | `GptLlmExecutor` |
| OpenAI (reasoning) | `gpt-5*`/`o3-`/`o4-` | `ReasoningLlmExecutor` |

새 provider 추가: `flow-core/.../adapter/provider/`에 `LlmExecutor` 구현 + `providers.yaml`의 `routing:`/`providers:`에 한 줄.

---

## Cancellation / Timeout 설계 결정

returney-flow는 **강제 cancellation을 제공하지 않는다**. 대신 두 종류의 timeout만 노출한다:

| 종류 | 위치 | 동작 |
|---|---|---|
| **Per-request HTTP timeout** | `providers.yaml` `defaults.requestTimeoutSec` | 1회 HTTP 호출의 hard upper bound. 초과 시 `LlmNetworkException` → retry/fallback 진입 |
| **Pipeline-level abandonment** | caller 측 `Future.orTimeout()` | caller가 응답 안 기다림. **백그라운드 호출은 계속 진행**되며 `onLlmCall`로 결과 기록 |

### 왜 강제 cancel을 안 하나

1. **이미 비용 발생**: LLM API 호출은 cancel해도 inference는 서버에서 끝까지 진행됨. 토큰 환불 없음.
2. **디버깅 가치**: 진행 중 호출의 결과(latency, tokens, error)는 사후 분석에 가치 큼. cancel하면 영영 모름.
3. **observability > forced cancel**: `ExecutionListener.onLlmCall`로 백그라운드 호출 결과가 항상 기록됨.

따라서 "사용자 X 버튼 누름" 같은 시나리오는 caller 측에서 응답 무시하고 다음 화면으로 가면 충분. 백그라운드 호출은 자연스럽게 완료/실패 후 lifecycle hook으로 추적된다.

```java
pipeline.run(prereqs, sessionId, nodeIds, config, listener)
    .orTimeout(10, TimeUnit.MINUTES)
    .whenComplete((result, error) -> {
        if (error instanceof TimeoutException) {
            // caller는 즉시 다음 작업으로
            // 백그라운드는 listener가 추적
        }
    });
```

---

## Extension Points (소비자 오버라이드 가능)

`*PipelineBase`의 `protected` 메서드를 재정의하면 라이브러리 동작을 갈아끼울 수 있다.

| 메서드 | 디폴트 | 재정의 사례 |
|---|---|---|
| `apiKeySupplier()` | 환경변수(`<NAME>_API_KEY`) | Spring Vault, AWS Secrets Manager |
| `rateLimiter()` | `SlidingWindowRateLimiter` (providers.yaml) | 분산 한도 (Redis) |
| `createLlmExecutor()` | `InternalLlmRouter` (providers.yaml) | 외부 게이트웨이 |
| `createLlmMiddleware(base)` | 기본 데코레이터 | 요청 로깅, prompt injection |

---

## 문서

- [docs/SPI.md](docs/SPI.md) — SPI 전체 명세, yaml 스키마, codegen 산출물, extension points 상세

---

## 빌드 / 테스트

```bash
./gradlew :flow-spi:jar :flow-core:test
```

flow-codegen은 standalone Gradle 프로젝트:
```bash
cd flow-codegen && ../gradlew compileGroovy
```

---

## 의존성

- flow-spi: JDK 21 only (외부 의존 0)
- flow-core: snakeyaml 2.3, gson 2.11
- flow-codegen: snakeyaml 2.3 (Groovy DSL)

flow-codegen은 Groovy로 작성됐다. 코드젠은 빌드타임 산출물이라 소비자 클래스패스에 노출되지 않으며, Gradle plugin 작성에 Groovy DSL이 자연스럽고 snakeyaml 동적 매핑과의 조합이 간결하다. flow-spi/flow-core는 100% Java.

---

## 동작 정책 노트

### Fan-out 부분 실패는 전체 실패로 취급

`scatter → llm → gather` 패턴에서 청크 1개가 실패하면 fan-out 노드 전체가
실패한다 (`CompletableFuture.join`이 RuntimeException으로 propagate). LLM
워크로드는 청크당 결과 일관성이 중요한 케이스가 많아 conservative default를 채택.

부분 허용이 필요한 use case는 caller가 chunk별로 파이프라인을 직접 호출 +
실패 흡수 로직을 구성한다.

### Fallback 모델 진입 시 backoff index 리셋

`InternalLlmRouter.attempt`는 primary 모델의 retry index가 끝나면 fallback
모델로 진입하면서 backoff index를 0부터 다시 시작한다. primary와 fallback이
서로 다른 capacity 모델이라는 가정 — primary의 누적 backoff를 fallback에
그대로 적용하지 않는다. 의도적 결정.

### ProGuard / R8 환경

Gson 2.11이 record 직렬화를 지원하지만, ProGuard/R8이 component name을
strip하면 `@SerializedName` 필드 매핑이 실패할 수 있다. 라이브러리를
ProGuard 환경에서 사용 시 keep rule 추가 권장:

```
-keepclassmembers class com.returney.flow.** {
    <init>(...);
}
-keepattributes Signature, RuntimeVisibleAnnotations
```

---

## 테스트 커버리지

- flow-core: 49개 단위/통합 테스트 (PipelineExecutor DAG 시나리오, InternalLlmRouter retry/fallback, SlidingWindowRateLimiter, ProvidersYamlParser 등)
- flow-codegen: FlowValidator 6 룰 + FlowModel + FlowRenderer 산출물 + FlowInterfaceGenerator e2e

```bash
./gradlew :flow-core:test
cd flow-codegen && ../gradlew test
```

---

## License

MIT — see [LICENSE](LICENSE).
