# flow-spi 명세

YAML 선언형 DAG LLM 파이프라인. 소비자는 yaml + codegen + 추상 클래스 구현으로 끝낸다. flow-core 내부(파서, 라우팅, 프로바이더 호출, capability/fallback, rate limiting, 프롬프트 렌더링)는 외부에 노출되지 않는다.

---

## 모듈 구조

```
returney-flow/
├── flow-spi/        # JDK only. 외부 의존 0.
└── flow-core/       # flow-spi에 api 의존. snakeyaml, gson.
```

`flow-codegen`은 별도 standalone Gradle 프로젝트로 소비자의 `buildSrc`가 `includeBuild`로 가져간다.

---

## 소비자 워크플로우

1. 파이프라인 yaml 작성 (`pipeline-flow.yaml`, `prompts/*.yaml`)
2. 빌드 시 codegen이 정합성을 검증하고 타입 안전한 `*PipelineBase` 추상 클래스를 생성
3. 소비자는 생성된 베이스를 상속해 server-node 메서드(`*Scatter/Gather/Transform`) 본문만 구현
4. 런타임: `pipeline.run(prerequisites, ...)` → core가 LLM 호출까지 전담

---

## flow-spi 노출 표면 (24개 클래스)

소비자가 직접 다루는 핵심: `Pipeline.run()` 호출 + `*PipelineBase`(생성됨) 상속 + `ExecutionListener` 구현.

### 진입 포트 (`com.returney.flow.port`)

| 인터페이스 | 역할 | 구현체 |
|---|---|---|
| `PipelineRunner` (in-port) | 파이프라인 실행 진입 | flow-core `PipelineExecutor` |
| `LlmExecutor` | LLM 호출 추상화 | flow-core `InternalLlmRouter` (디폴트) |
| `PromptRenderer` | 프롬프트 템플릿 렌더링 | flow-core `ClasspathPromptRenderer` |
| `NodeOutputExtractor` | 노드 출력 필드 추출 | codegen 생성 (`*FieldExtractor`) |
| `RateLimiter` | RPM/TPM 한도 관리 (Reservation 기반) | flow-core `SlidingWindowRateLimiter`, 모델별 윈도우 |
| `ExecutionListener` | 노드/플로우/LLM 이벤트 콜백 | 소비자 구현 (e.g. DB 적재) |
| `ServerNodeExecutor` | scatter/gather/transform 서버 로직 | codegen 생성 (`*PipelineBase`의 익명 클래스) |
| `ApiKeySupplier` | 프로바이더 API 키 공급 | 디폴트: 환경변수 |

### 데이터 record (`com.returney.flow.domain`)

| 패키지 | 클래스 |
|---|---|
| `domain.definition` | `PipelineDefinition`, `PipelineNode`, `PipelineEdge`, `NodeType`, `PipelineParseException` |
| `domain.execution` | `ExecutionConfig`, `NodeResult`, `NodeStatus`, `PipelineResult` |
| `domain.llm` | `LlmRequest`, `LlmRawResponse`, `LlmCallEvent`, `LlmCallException` (+ 3종 서브클래스) |

---

## ExecutionListener — 노드/LLM 라이프사이클 훅

```java
public interface ExecutionListener {
  void onNodeStarted(String nodeId, long timestamp);
  void onNodeCompleted(String nodeId, NodeResult result);
  void onNodeFailed(String nodeId, String error);
  void onNodeSkipped(String nodeId);
  void onFlowCompleted(PipelineResult result);

  /** LLM 호출 1회마다 호출 (성공/실패 모두). 디폴트 no-op. */
  default void onLlmCall(LlmCallEvent event) {}

  static ExecutionListener noop() { ... }
}
```

`onLlmCall(event)`로 cost 계산, 토큰 적재, 메트릭, fallback 추적이 가능하다. `event.attemptIndex()`가 0이면 1차, 1+이면 fallback 시도.

---

## flow-core 내부 (소비자 비공개)

```
com.returney.flow/
├── FlowCore                         # 부트스트랩 팩토리 (codegen만 사용)
├── application/
│   ├── PipelineExecutor             # DAG 실행 엔진 (PipelineRunner 구현)
│   ├── NodeExecutor
│   ├── LlmNodeRunner                # fan-out 포함 LLM 노드 실행
│   ├── NodeInputResolver
│   ├── CacheInvalidator
│   ├── ExecutionContext             # 런타임 가변 상태
│   └── InternalLlmRouter            # 모델 → 프로바이더 라우팅 + capability + fallback
└── adapter/
    ├── parser/
    │   ├── PipelineYamlParser       # pipeline-flow.yaml 로더
    │   ├── ProvidersYamlParser      # providers.yaml 로더
    │   ├── ProvidersConfig
    │   └── RateLimitYamlParser
    ├── prompt/
    │   └── ClasspathPromptRenderer
    ├── common/
    │   ├── HttpUtil
    │   └── SlidingWindowRateLimiter
    └── provider/
        ├── anthropic/ClaudeLlmExecutor
        ├── gemini/GeminiLlmExecutor + GeminiConfig
        └── openai/{GptLlmExecutor, ReasoningLlmExecutor}
```

---

## YAML 계약

### `pipeline-flow.yaml`

```yaml
name: my-pipeline
version: 1

prerequisites:
  - sessionId
  - reportText

nodes:
  - id: chunk_splitter
    type: scatter
    critical: false

  - id: analyze
    type: llm
    action: analyze        # prompts/analyze.yaml의 action 필드와 일치
    critical: true
    result:
      type: com.example.AnalysisResult   # 결과 record FQCN
    inputs:
      text: Prerequisites.reportText
      chunks: chunk_splitter
      field: upstream.someField
```

### `prompts/{filename}.yaml`

```yaml
action: analyze            # 필수, 노드의 action과 일치
model: claude-sonnet-4-6
thinking: 0                # 0=off, 양수=토큰 예산, true=-1(기본)

# 단일 모드
promptTemplate: |
  분석하세요. {{text}}

# OR 대화 모드 (system/user 분리, 시스템 프롬프트는 caching 대상)
systemTemplate: |
  매 턴 동일한 인스트럭션. {{methodology:full_prompt}}
userTemplate: |
  {{userMessage}}

# 분기 참조용 섹션 — {{methodology:full_prompt}}로 lookup
methodology:
  STAR:
    full_prompt: |
      STAR 가이드...
```

### `providers.yaml` (flow-core 디폴트 / 소비자 오버라이드 가능)

```yaml
providers:
  gemini:
    type: gemini
    baseUrl: https://generativelanguage.googleapis.com/v1beta/models
    apiKeyName: gemini    # ApiKeySupplier에 전달할 이름
  anthropic:
    type: anthropic
    baseUrl: https://api.anthropic.com
    apiKeyName: anthropic
  openai:
    type: openai
    baseUrl: https://api.openai.com
    apiKeyName: openai
  openai-reasoning:
    type: openai-reasoning
    baseUrl: https://api.openai.com
    apiKeyName: openai

routing:
  - prefix: "gemini-"
    provider: gemini
  - prefix: "claude-"
    provider: anthropic
  - prefix: "gpt-5"          # 더 긴 prefix가 위에 와야 함
    provider: openai-reasoning
  - prefix: "gpt-"
    provider: openai

default: gemini-2.5-flash    # request.model이 비면 이 값으로 보강

models:                      # 모델별 attribute. 라우팅과 별개로 모델 단위 설정.
  claude-sonnet-4-6:
    supportsThinking: true
    thinkingMaxBudget: 32000
    rate: { rpm: 50, tpm: 80000 }   # 60초 슬라이딩 윈도우. 미명시면 무제한

fallback:                    # 1단 fallback. exact → prefix → 'default'
  claude-sonnet-4-6: claude-haiku-4-5
  default: gemini-2.5-flash-lite

retry:                       # transient 오류 재시도. 미명시 시 RetryPolicy.DEFAULT
  maxAttempts: 3             # 모델당 총 시도 수 (1=재시도 없음)
  initialDelayMs: 500
  maxDelayMs: 10000
  backoffMultiplier: 2.0
  jitter: 0.2                # 0~1 비율
```

### Retry / fallback 동작

```
attempt(primary, idx=0)
├── LlmTransientException / LlmNetworkException → backoff 후 재시도 (총 maxAttempts회)
├── LlmClientErrorException 등 permanent → 즉시 throw, fallback도 안 감
└── 모든 재시도 소진 → fallback 모델로 같은 retry 사이클 (idx=maxAttempts부터)
```

각 attempt마다 `ExecutionListener.onLlmCall(event)` 발행 — `event.attemptIndex()`가
0부터 maxAttempts-1까지면 primary, maxAttempts 이상이면 fallback.

---

## API 키 공급

기본: `providers.yaml`의 `apiKeyName` → `System.getenv("<NAME>_API_KEY")`. 예: `apiKeyName: anthropic` → `ANTHROPIC_API_KEY`.

오버라이드: codegen이 만드는 `*PipelineBase`에 `protected ApiKeySupplier apiKeySupplier()` 메서드 재정의.

```java
@Override
protected ApiKeySupplier apiKeySupplier() {
    return providerName -> springVault.lookup(providerName);
}
```

`createLlmExecutor()`도 재정의 가능 — 외부 게이트웨이를 쓰려면 `LlmExecutor` 구현을 직접 반환.

---

## codegen 빌드타임 검증

빌드 시 다음 정합성을 자동 확인. **strict** 위반 시 빌드 실패, **lenient** 위반 시 stderr 경고만.

| 검증 | 종류 |
|---|---|
| 노드 ID 중복 | strict |
| `inputs:` 소스가 미존재 노드 참조 | strict |
| 순환 의존 (Kahn's algorithm) | strict |
| `Prerequisites.x` 참조가 prerequisites에 없음 | strict |
| `prompts/*.yaml`의 `action` ↔ pipeline node `action` 매칭 | lenient |
| prompt 템플릿의 `{{var}}` ↔ 노드 `inputs:` 키 매칭 | lenient |

`{{key:section}}` 형태(콜론 포함)는 yaml 내부 분기 참조로 간주되어 검증 대상에서 제외된다.

---

## 코드젠 생성 산출물

소비자 빌드 디렉터리에 다음이 생성됨:

| 파일 | 역할 |
|---|---|
| `*Prerequisites.java` | 외부 입력 record (yaml `prerequisites:` 기반) |
| `*Result.java` | 최종 출력 record (yaml `result.type:` 가진 노드 모음) |
| `*FieldExtractor.java` | `NodeOutputExtractor` 구현 (yaml `inputs: nodeId.field` 기반) |
| `*LlmMiddleware.java` | `LlmExecutor` 데코레이터 (오버라이드 지점) |
| `*PipelineBase.java` | abstract — 소비자가 상속하는 진입 클래스 |

---

## 소비자 진입 코드 (전체 예)

```java
@Component
public class AnalysisPipeline extends AnalysisPipelineBase {

    public AnalysisPipeline(@Qualifier("analysisExecutor") Executor exec) {
        super(exec);
    }

    @Override
    protected List<String> chunkSplitterScatter(Map<String, String> inputs) {
        return Arrays.asList(inputs.get("text").split("\n\n"));
    }

    @Override
    protected String chunkSummariesGather(List<String> chunks) {
        return String.join("\n", chunks);
    }

    public AnalysisPipelineResult execute(String reportText, UUID sessionId) {
        var prereqs = AnalysisPipelinePrerequisites.from(Map.of("reportText", reportText));
        return run(prereqs, sessionId, DEFINITION.nodeIds(),
            ExecutionConfig.defaults(), ExecutionListener.noop());
    }
}
```

LLM API 키는 환경변수(`GEMINI_API_KEY`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`)에서 자동 로드. 별도 와이어링 불필요.
