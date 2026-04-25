class FlowRenderer {

    static String prerequisites(FlowModel m) {
        def fields = m.prerequisites.collect { "    String ${it}" }.join(',\n')
        def toMapEntries = m.prerequisites.collect { '            map.put("' + it + '", ' + it + ');' }.join('\n')
        def fromArgs = m.prerequisites.collect { '            map.get("' + it + '")' }.join(',\n')

        """\
package ${m.pkg};

import java.util.HashMap;
import java.util.Map;

/** 파이프라인 외부 입력 계약. Gradle 코드젠으로 생성됨 — 직접 수정 금지. */
public record ${m.flowName}Prerequisites(
${fields}
) {
    public static ${m.flowName}Prerequisites from(Map<String, String> map) {
        return new ${m.flowName}Prerequisites(
${fromArgs}
        );
    }

    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();
${toMapEntries}
        return map;
    }
}
"""
    }

    static String resultRecord(FlowModel m) {
        def importBlock = importsBlock(m.resultNodes.collect { it.importLine }.findAll { it })
        def fields = m.resultNodes.collect { "    ${it.javaType} ${it.field}" }.join(',\n')

        """\
package ${m.pkg};
${importBlock}
/** 파이프라인 최종 출력. Gradle 코드젠으로 생성됨 — 직접 수정 금지. */
public record ${m.flowName}Result(
${fields}
) {}
"""
    }

    static String fieldExtractor(FlowModel m) {
        if (m.fieldRefs.isEmpty()) {
            return """\
package ${m.pkg};

import com.returney.flow.port.NodeOutputExtractor;

/** Gradle 코드젠으로 생성됨 — 직접 수정 금지. */
public class ${m.flowName}FieldExtractor implements NodeOutputExtractor {

    @Override
    public String extract(String nodeId, String fieldName, String output) {
        throw new IllegalArgumentException(
            "Unknown field reference: " + nodeId + "." + fieldName);
    }
}
"""
        }

        def importBlock = importsBlock(m.fieldRefs.collect { it.importLine }.findAll { it })
        def cases = m.fieldRefs.collect { ref ->
            """\
            case "${ref.nodeId}.${ref.fieldName}" ->
                GSON.fromJson(output, ${ref.javaType}.class).${ref.accessorName}();"""
        }.join('\n')

        """\
package ${m.pkg};

import com.google.gson.Gson;
import com.returney.flow.port.NodeOutputExtractor;${importBlock}
/** Gradle 코드젠으로 생성됨 — 직접 수정 금지. */
public class ${m.flowName}FieldExtractor implements NodeOutputExtractor {

    private static final Gson GSON = new Gson();

    @Override
    public String extract(String nodeId, String fieldName, String output) {
        return switch (nodeId + "." + fieldName) {
${cases}
            default -> throw new IllegalArgumentException(
                "Unknown field reference: " + nodeId + "." + fieldName);
        };
    }
}
"""
    }

    static String llmMiddleware(FlowModel m) {
        """\
package ${m.pkg};

import com.returney.flow.domain.llm.LlmCallException;
import com.returney.flow.domain.llm.LlmRawResponse;
import com.returney.flow.domain.llm.LlmRequest;
import com.returney.flow.port.LlmExecutor;
import java.util.Map;
import java.util.UUID;

/**
 * ${m.flowName} 파이프라인 LLM 미들웨어. Gradle 코드젠으로 생성됨 — 직접 수정 금지.
 *
 * <p>서브클래싱하여 {@link #beforeExecute}를 재정의하면 LLM 호출 전 요청을 가공할 수 있다.
 */
public class ${m.flowName}LlmMiddleware implements LlmExecutor {

    private final LlmExecutor delegate;

    public ${m.flowName}LlmMiddleware(LlmExecutor delegate) {
        this.delegate = delegate;
    }

    @Override
    public LlmRawResponse execute(LlmRequest request) throws LlmCallException {
        return delegate.execute(beforeExecute(request));
    }

    protected LlmRequest beforeExecute(LlmRequest request) {
        return request;
    }

    @Override
    public void setSessionId(UUID sessionId) {
        delegate.setSessionId(sessionId);
    }

    @Override
    public void setContext(String action, Map<String, String> variables) {
        delegate.setContext(action, variables);
    }
}
"""
    }

    static String pipelineBase(FlowModel m) {
        def importBlock = importsBlock(m.resultNodes.collect { it.importLine }.findAll { it })

        // LLM actions list for ClasspathPromptRenderer.forActions(...)
        def actionsArg = m.llmActions.collect { '"' + it + '"' }.join(', ')

        // Abstract server node methods
        def abstractMethods = [
            m.scatterNodes.collect { """\
    protected abstract java.util.List<String> ${it.methodName.replace('Scatter', '')}Scatter(java.util.Map<String, String> inputs);""" },
            m.gatherNodes.collect { """\
    protected abstract String ${it.methodName.replace('Gather', '')}Gather(java.util.List<String> chunks);""" },
            m.transformNodes.collect { """\
    protected abstract String ${it.methodName.replace('Transform', '')}Transform(java.util.Map<String, String> inputs);""" }
        ].flatten().join('\n\n')

        // supports() cases
        def supportedIds = m.serverNodes.collect { '"' + it.id + '"' }.join(', ')

        def scatterCases = switchOrThrow(
            m.scatterNodes.collect { """                case "${it.id}" -> ${it.methodName.replace('Scatter', '')}Scatter(inputs);""" },
            "scatter")
        def gatherCases = switchOrThrow(
            m.gatherNodes.collect { """                case "${it.id}" -> ${it.methodName.replace('Gather', '')}Gather(chunks);""" },
            "gather")
        def transformCases = switchOrThrow(
            m.transformNodes.collect { """                case "${it.id}" -> ${it.methodName.replace('Transform', '')}Transform(inputs);""" },
            "transform")

        // parseResult blocks — parseExpr uses "result.nodeResults()..." so param must be "result"
        def parseBlocks = m.resultNodes.collect { node ->
            def hookCall = node.hook
                ? "\n        if (${node.field} != null) on${node.field.capitalize()}(${node.field});"
                : ""
            """\
        ${node.javaType} ${node.field} = null;
        if (!result.failedNodes().contains("${node.id}") && result.nodeResults().containsKey("${node.id}")) {
            ${node.field} = ${node.parseExpr};
        }${hookCall}"""
        }.join('\n\n')

        // hook methods (protected no-op, one per hook: true node)
        def hookMethods = m.resultNodes.findAll { it.hook }.collect { node ->
            "    protected void on${node.field.capitalize()}(${node.javaType} result) {}"
        }.join('\n\n')

        def resultArgs = m.resultNodes.collect { it.field }.join(', ')

        // isCriticalFailure: critical nodes
        def criticalIds = m.resultNodes.findAll { it.critical }.collect { '"' + it.id + '"' }.join(', ')
        def criticalCheck = criticalIds
            ? "var critical = java.util.Set.of(${criticalIds});\n        return raw.failedNodes().stream().anyMatch(critical::contains);"
            : "return false;"

        """\
package ${m.pkg};

import com.google.gson.Gson;
import com.returney.flow.adapter.parser.PipelineYamlParser;
import com.returney.flow.adapter.prompt.ClasspathPromptRenderer;
import com.returney.flow.application.InternalLlmRouter;
import com.returney.flow.domain.definition.PipelineDefinition;
import com.returney.flow.domain.execution.ExecutionConfig;
import com.returney.flow.domain.execution.PipelineResult;
import com.returney.flow.port.ApiKeySupplier;
import com.returney.flow.FlowCore;
import com.returney.flow.port.ExecutionListener;
import com.returney.flow.port.LlmExecutor;
import com.returney.flow.port.PipelineRunner;
import com.returney.flow.port.PromptRenderer;
import com.returney.flow.port.ServerNodeExecutor;${importBlock}
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Gradle 코드젠으로 생성됨 — 직접 수정 금지. */
public abstract class ${m.flowName}Base {

    private static final Gson GSON = new Gson();

    protected static final PipelineDefinition DEFINITION =
        new PipelineYamlParser().parseFromClasspath("pipeline-flow.yaml");

    private static final PromptRenderer RENDERER =
        ClasspathPromptRenderer.forActions(${actionsArg});

    private final Executor executor;
    private volatile LlmExecutor cachedLlmExecutor;

    protected ${m.flowName}Base(Executor executor) {
        this.executor = executor;
    }

    /**
     * 파이프라인을 실행하고 타입이 지정된 결과를 반환한다.
     *
     * @param prerequisites 외부 입력
     * @param sessionId 실행 세션 ID
     * @param nodeIds 실행할 노드 집합
     * @param config 실행 설정 (모델 오버라이드 등)
     * @param listener 실행 이벤트 리스너 (null → no-op)
     */
    public final ${m.flowName}Result run(
        ${m.flowName}Prerequisites prerequisites,
        UUID sessionId,
        Set<String> nodeIds,
        ExecutionConfig config,
        ExecutionListener listener) {

        LlmExecutor middleware = createLlmMiddleware(llmExecutor());
        middleware.setSessionId(sessionId);
        ServerNodeExecutor serverNodes = buildServerNodeExecutor();
        ExecutionListener el = listener != null ? listener : ExecutionListener.noop();

        PipelineRunner runner = FlowCore.create(
            middleware, RENDERER, serverNodes,
            new ${m.flowName}FieldExtractor(), el, executor);

        PipelineResult raw = runner.run(
            DEFINITION, sessionId.toString(), nodeIds,
            config, null, prerequisites.toMap()).join();

        return parseResult(raw);
    }

    /** 크리티컬 노드 실패 여부를 반환한다. */
    public final boolean isCriticalFailure(${m.flowName}Result result) {
        // 크리티컬 필드가 null이면 실패로 판단
        ${m.resultNodes.findAll { it.critical }.collect { "if (result.${it.field}() == null) return true;" }.join('\n        ')}
        return false;
    }

    /**
     * API 키 공급자. 기본은 환경변수({@code <NAME>_API_KEY}).
     * Spring Vault 등을 쓰려면 재정의한다.
     */
    protected ApiKeySupplier apiKeySupplier() {
        return ApiKeySupplier.fromEnv();
    }

    /**
     * LLM 실행기 팩토리. 기본은 {@link InternalLlmRouter} (providers.yaml 기반 라우팅).
     * 외부 게이트웨이 등을 쓰려면 재정의한다.
     */
    protected LlmExecutor createLlmExecutor() {
        return InternalLlmRouter.from(
            com.returney.flow.adapter.parser.ProvidersYamlParser.loadFromClasspath(),
            apiKeySupplier());
    }

    private LlmExecutor llmExecutor() {
        LlmExecutor local = cachedLlmExecutor;
        if (local == null) {
            synchronized (this) {
                local = cachedLlmExecutor;
                if (local == null) {
                    local = createLlmExecutor();
                    cachedLlmExecutor = local;
                }
            }
        }
        return local;
    }

    /** LLM 미들웨어 팩토리. 재정의하여 LLM 호출을 가로챌 수 있다. */
    protected LlmExecutor createLlmMiddleware(LlmExecutor base) {
        return new ${m.flowName}LlmMiddleware(base);
    }

${hookMethods ? hookMethods + '\n\n' : ''}${abstractMethods}

    // ── internal ─────────────────────────────────────────────────────────────

    private ServerNodeExecutor buildServerNodeExecutor() {
        return new ServerNodeExecutor() {
            @Override
            public boolean supports(String nodeId) {
                return java.util.Set.of(${supportedIds}).contains(nodeId);
            }
            @Override
            public List<String> scatter(String nodeId, Map<String, String> inputs) {
                ${scatterCases}
            }
            @Override
            public String gather(String nodeId, List<String> chunks) {
                ${gatherCases}
            }
            @Override
            public String transform(String nodeId, Map<String, String> inputs) {
                ${transformCases}
            }
        };
    }

    private ${m.flowName}Result parseResult(PipelineResult result) {
${parseBlocks}

        return new ${m.flowName}Result(${resultArgs});
    }
}
"""
    }

    // ── template utilities ────────────────────────────────────────────────────

    private static String switchOrThrow(List<String> cases, String label) {
        if (cases.isEmpty()) {
            return """throw new IllegalArgumentException("No ${label} nodes in this pipeline: " + nodeId);"""
        }
        """\
return switch (nodeId) {
${cases.join('\n')}
                default -> throw new IllegalArgumentException("Unknown ${label} node: " + nodeId);
            };"""
    }

    private static String importsBlock(List<String> importLines) {
        if (!importLines) return ""
        def unique = importLines.unique()
        "\n${unique.join('\n')}"
    }
}
