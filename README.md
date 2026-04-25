# returney-flow

[![CI](https://github.com/moongua404/returney-flow/actions/workflows/ci.yml/badge.svg)](https://github.com/moongua404/returney-flow/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

YAML 선언형 DAG LLM 파이프라인 라이브러리.

소비자는 yaml로 파이프라인을 선언하고 빌드 시 코드젠으로 타입 안전한 진입 클래스를 받는다.
LLM 호출(라우팅·재시도·rate limit·fallback)은 라이브러리가 전담한다.

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
