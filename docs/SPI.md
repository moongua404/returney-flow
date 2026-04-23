# flow-core SPI 명세

YAML 선언형 DAG 기반 LLM 파이프라인 라이브러리. 파이프라인을 YAML로 선언하면 `PipelineExecutor`가 위상 정렬로 실행 순서를 결정하고 독립 노드를 Java Virtual Thread로 병렬 실행한다.

라이브러리는 Spring·JPA·LLM SDK에 의존하지 않는다. 외부 동작은 6개 포트(SPI)로 추상화되어 있으며 구현은 호출자가 주입한다.

---

## 포트 (Port)

| 포트 | 역할 | Backend 구현체 |
|---|---|---|
| `LlmExecutor` | LLM API 호출 | `LlmExecutorAdapter` |
| `PromptRenderer` | 프롬프트 템플릿 렌더링 | `PromptRendererAdapter` |
| `NodeOutputExtractor` | 노드 출력에서 필드 추출 | 코드젠 생성 (`*FieldExtractor`) |
| `RateLimiter` | 요청/토큰 한도 관리 | `NoOpRateLimiter` (실제: `LlmProviderRouter`) |
| `ExecutionListener` | 실행 이벤트 수신 | `PipelineExecutionListener` |
| `ServerNodeExecutor` | 서버 커스텀 노드 실행 | 코드젠 생성 (`*ServerNodeExecutor`) |

---

### LlmExecutor

```java
public interface LlmExecutor {
    default void setSessionId(UUID sessionId) {}
    default void setContext(String action, Map<String, String> variables) {}
    LlmRawResponse execute(LlmRequest request) throws LlmCallException;
}
```

`execute()` 직전 `setContext()`를 호출해 로깅용 컨텍스트를 설정한다. `LlmRawResponse`는 `text()`, `totalTokens()` 등을 가진 record다.

**LlmRequest 팩토리:**

```java
LlmRequest.single(prompt, model, thinkingBudget)
LlmRequest.conversation(systemPrompt, messages, model, thinkingBudget, new CacheConfig(true))
LlmRequest.multimodal(textPrompt, binaryContent, mimeType, model, thinkingBudget)
```

---

### PromptRenderer

```java
public interface PromptRenderer {
    String render(String action, Map<String, String> variables);

    default String getModel(String action) { return null; }
    default int getThinkingBudget(String action) { return -1; }
    default String renderSystemPrompt(String action, Map<String, String> variables) { return null; }
    default String renderUserPrompt(String action, Map<String, String> variables) { return null; }
}
```

`renderSystemPrompt()`가 null이 아니면 대화형 모드(`LlmRequest.conversation`)로, null이면 단일 모드(`LlmRequest.single`)로 요청을 빌드한다.

---

### NodeOutputExtractor

```java
@FunctionalInterface
public interface NodeOutputExtractor {
    String extract(String nodeId, String fieldName, String nodeOutput);
}
```

`nodeId.fieldName` 형태의 입력 소스 참조를 해석한다. Gradle 코드젠이 YAML의 `result.type` 정보로부터 구현체(`*FieldExtractor.java`)를 자동 생성한다. 직접 구현하지 않는다.

---

### RateLimiter

```java
public interface RateLimiter {
    void acquire(String model, String sessionId, int estimatedTokens) throws InterruptedException;
    void correct(String model, String sessionId, int actual);
}
```

`LlmExecutorAdapter.execute()` 전후로 호출된다. Backend는 `NoOpRateLimiter`를 주입하며 실제 한도는 `LlmProviderRouter`가 처리한다. 독립 사용 시 `SlidingWindowRateLimiter`(60초 슬라이딩 윈도우, RPM/TPM) 구현체가 제공된다.

---

### ExecutionListener

```java
public interface ExecutionListener {
    void onNodeStarted(String nodeId, long timestamp);
    void onNodeCompleted(String nodeId, NodeResult result);
    void onNodeFailed(String nodeId, String error);
    void onNodeSkipped(String nodeId);
    void onFlowCompleted(PipelineResult result);
}
```

---

### ServerNodeExecutor

```java
public interface ServerNodeExecutor {
    boolean supports(String nodeId);
    List<String> scatter(String nodeId, Map<String, String> inputs);
    String gather(String nodeId, List<String> chunks);
    String transform(String nodeId, Map<String, String> inputs);
}
```

`SCATTER` / `GATHER` / `TRANSFORM` 타입 노드의 서버 로직을 구현한다. 코드젠이 구현 골격(`*ServerNodeExecutor.java`)을 생성하고 호출자가 메서드 본문을 채운다.

---

## 노드 타입

| 타입 | 동작 |
|---|---|
| `LLM` | `PromptRenderer`로 프롬프트를 빌드해 `LlmExecutor`를 호출한다 |
| `LLM` (fan-out) | 업스트림 `SCATTER` 결과가 있으면 청크마다 Virtual Thread로 LLM을 병렬 실행한다 |
| `TEMPLATE` | `PromptRenderer.render()` 결과 자체를 출력으로 사용한다 (LLM 호출 없음) |
| `SCATTER` | `ServerNodeExecutor.scatter()`를 호출해 입력을 청크 목록으로 분해한다 |
| `GATHER` | 업스트림 scatter 결과를 모아 `ServerNodeExecutor.gather()`로 병합한다 |
| `TRANSFORM` | `ServerNodeExecutor.transform()`을 호출해 입력을 단일 문자열로 변환한다 |

노드 실패 시 해당 노드와 모든 다운스트림이 `FAILED` / `SKIPPED` 처리된다. 다른 브랜치는 계속 실행된다.

---

## 입력 소스 문법

파이프라인 YAML의 `inputs` 섹션에서 각 변수의 값 소스를 지정한다.

| 형식 | 의미 |
|---|---|
| `Prerequisites.name` | 파이프라인 외부 입력 (`prerequisites` 선언 필요) |
| `nodeId` | 해당 노드의 output 전체 (String) |
| `nodeId.fieldName` | 해당 노드 output을 JSON 역직렬화 후 `fieldName` 추출 |

`nodeId.fieldName`은 코드젠이 컴파일 타임에 타입 안전하게 처리한다. 업스트림 노드의 `result.type`이 builtin(`String`, `Integer` 등)이면 dot-access는 허용되지 않는다.

---

## YAML 계약

### pipeline-flow.yaml

```yaml
name: my-pipeline
version: 1

prerequisites:        # Prerequisites.xxx 참조를 사용하려면 선언 필요
  - sessionId
  - reportText

nodes:
  - id: chunk_splitter
    type: scatter
    critical: false   # true이면 실패 시 전체 파이프라인 즉시 중단

  - id: analyze
    type: llm
    action: analyze   # prompts/analyze.yaml과 매핑
    critical: true
    result:
      type: com.example.AnalysisResult   # 코드젠 결과 record FQCN
    inputs:
      text: Prerequisites.reportText     # 외부 입력
      chunks: chunk_splitter             # 다른 노드 output 전체
      field: upstream.someField          # 다른 노드 output의 특정 필드
```

**파싱 검증** (`PipelineParseException`):
- 노드 ID 중복
- `inputs` 소스가 존재하지 않는 노드를 참조
- 순환 의존

**코드젠 검증** (빌드 시):
- `Prerequisites.xxx` 참조가 `prerequisites` 목록에 없는 이름을 사용

---

### prompts/{action}.yaml

```yaml
action: analyze        # 필수. 노드의 action 값과 일치해야 함
model: claude-sonnet-4-6
thinking: 0            # 0: OFF, 양수: 토큰 예산

# ── 단일 프롬프트 모드 ───────────────────────────────
promptTemplate: |
  분석하세요.
  {{text}}

# ── 대화형 모드 (system/user 분리) ──────────────────
# systemTemplate이 존재하면 대화형 모드로 전송됨
# systemTemplate은 Anthropic 프롬프트 캐싱 대상
systemTemplate: |
  매 턴 동일한 인스트럭션 → 캐시 적용.
  {{methodology:full_prompt}}   # 중첩 섹션 참조

userTemplate: |
  매 턴 바뀌는 동적 컨텍스트.
  {{userMessage}}

methodology:           # {{methodology:full_prompt}} 치환에 사용
  STAR:
    full_prompt: |
      STAR 가이드...
```

**변수 치환 규칙:**
- `{{varName}}` → `variables.get("varName")`
- `{{key:field}}` → `variables.get("key")`를 키로 삼아 YAML 내 `key.{value}.field` 값 삽입

---

## 조립 (Wiring)

```java
// 코드젠이 생성한 구현체
NodeInputResolver inputResolver =
    new NodeInputResolver(new AnalysisPipelineFieldExtractor());

LlmNodeRunner llmNodeRunner =
    new LlmNodeRunner(llmExecutor, promptRenderer, inputResolver, executor);

NodeExecutor nodeExecutor =
    new NodeExecutor(llmNodeRunner, serverNodeExecutor, inputResolver, listener);

PipelineExecutor pipeline =
    new PipelineExecutor(nodeExecutor, listener, executor);

// 실행
PipelineDefinition definition = PipelineYamlParser.parse(yamlString);

CompletableFuture<PipelineResult> future = pipeline.executeNodes(
    definition,
    sessionId,                   // String
    nodeIds,                     // Set<String> — 실행할 노드 집합
    ExecutionConfig.defaults(),  // 또는 .withModel("claude-sonnet-4-6")
    null,                        // Map<String, NodeResult> seed (선택)
    prerequisites);              // Map<String, String>
```
