/**
 * 코드젠이 emit하는 단일 산출물 *Base.java 의 텍스트를 만든다.
 *
 * <p>이전엔 파이프라인당 5개 파일(Prerequisites/Result/FieldExtractor/LlmMiddleware/Base)을
 * 뱉었지만, 다음 이유로 Base 하나로 통합:
 * <ul>
 *   <li>Prerequisites는 Map&lt;String,String&gt;을 typed record로 감쌀 뿐, 호출부는 항상
 *       Map.of(...)로 만들어 from()을 거쳤음 → 단순 라운드트립 보일러플레이트</li>
 *   <li>Result는 단일 결과 파이프라인(대다수)에선 wrapper 의미가 없고, 다중 결과 케이스만
 *       호출자가 도메인 record를 직접 정의해 yaml output.type 으로 참조하면 됨</li>
 *   <li>FieldExtractor는 Base 안 anonymous class로 inline (cross-node refs 있으면 switch,
 *       없으면 throw default — 어차피 NodeOutputExtractor가 호출 안 됨)</li>
 *   <li>LlmMiddleware는 모든 파이프라인이 동일 boilerplate라 flow-core/middleware/LlmMiddleware
 *       하나만 두고 모두 공유</li>
 * </ul>
 */
class FlowRenderer {

    static String pipelineBase(FlowModel m) {
        [
            renderHeader(m),
            renderFieldsAndConstructor(m),
            renderPublicMethods(m),
            renderUserOverrides(m),
            renderInternals(m),
        ].join('\n\n')
    }

    /**
     * 서버 노드 콜백 인터페이스. SPI가 앱을 호출하는 방향(in-port)으로 쓰인다.
     * 서버 노드가 없는 파이프라인이면 null 반환.
     */
    static String serverNodesInterface(FlowModel m) {
        if (m.serverNodes.isEmpty()) return null

        def methods = [
            m.scatterNodes.collect { node ->
                def mn = node.methodName.replace('Scatter', '') + 'Scatter'
                "    java.util.List<String> ${mn}(java.util.Map<String, Object> inputs);"
            },
            m.gatherNodes.collect { node ->
                def mn = node.methodName.replace('Gather', '') + 'Gather'
                "    String ${mn}(java.util.List<String> chunks);"
            },
            m.transformNodes.collect { node ->
                def mn = node.methodName.replace('Transform', '') + 'Transform'
                node.typed
                    ? "    com.returney.flow.domain.execution.NodeOutput ${mn}(java.util.Map<String, Object> inputs);"
                    : "    String ${mn}(java.util.Map<String, Object> inputs);"
            }
        ].flatten().join('\n\n')

        """\
package ${m.pkg};

import java.util.List;
import java.util.Map;

/** Gradle 코드젠으로 생성됨 — 직접 수정 금지. */
public interface ${m.flowName}ServerNodes {

${methods}
}
"""
    }

    /**
     * 파이프라인 실행 진입점. 앱이 flow-core를 호출하는 방향(out-adapter)으로 쓰인다.
     * 서버 노드가 있으면 생성자에서 {FlowName}ServerNodes를 주입받아 위임한다.
     */
    static String pipelineRunner(FlowModel m) {
        [
            renderHeaderForRunner(m),
            renderFieldsAndConstructorForRunner(m),
            renderPublicMethodsForRunner(m),
            renderInternalsForRunner(m),
        ].join('\n\n')
    }

    /** {FlowName}Input record 내용 반환. 타입 있는 prerequisites를 record 컴포넌트로 emit. */
    static String inputRecord(FlowModel m) {
        def sessionPkg = 'java.util.UUID'
        def imports = ['import java.util.UUID;']
        m.prerequisites.findAll { it.fqcn != 'java.lang.String' && !it.fqcn.startsWith('java.lang.') && !it.fqcn.startsWith('boolean') && !it.fqcn.startsWith('int') && !it.fqcn.startsWith('long') }.each { p ->
            if (p.fqcn == 'java.util.UUID') { /* already imported */ }
            else imports << "import ${p.fqcn};"
        }
        def allPrereqs = m.prerequisites
        def components = ['    UUID sessionId']  // sessionId is always present in Input
        allPrereqs.findAll { it.name != 'sessionId' }.each { p ->
            components << "    ${javaParamType(p.fqcn)} ${p.name}"
        }
        def importBlock = imports.unique().collect { "\n${it}" }.join('')
        """\
package ${m.pkg};
${importBlock}

/** Gradle 코드젠으로 생성됨 — 직접 수정 금지. */
public record ${m.flowName}Input(
${components.join(',\n')}) {}
"""
    }

    // ── header / fields ────────────────────────────────────────────────────

    private static String renderHeader(FlowModel m) {
        def prereqImports = m.prerequisites
            .findAll { it.fqcn != 'java.lang.String' && !it.fqcn.startsWith('java.lang.')
                && !it.fqcn.startsWith('boolean') && !it.fqcn.startsWith('int')
                && !it.fqcn.startsWith('long') && it.fqcn != 'java.util.UUID' }
            .collect { "import ${it.fqcn};" }
        def importLines = prereqImports + [m.outputImportLine] +
            m.resultNodes.collect { it.importLine } +
            m.fieldRefs.collect { it.importLine }
        def importBlock = importsBlock(importLines.findAll { it })
        """\
package ${m.pkg};

import com.google.gson.Gson;
import com.returney.flow.domain.definition.PipelineDefinition;
import com.returney.flow.domain.execution.ExecutionConfig;
import com.returney.flow.domain.execution.PipelineResult;
import com.returney.flow.port.ExecutionListener;
import com.returney.flow.port.LlmExecutor;
import com.returney.flow.port.NodeOutputExtractor;
import com.returney.flow.port.PipelineRunner;
import com.returney.flow.port.PipelineRunnerFactory;
import com.returney.flow.port.PromptRenderer;
import com.returney.flow.port.ServerNodeExecutor;
import com.returney.flow.util.LlmJsonExtractor;
import java.util.ServiceLoader;${importBlock}
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Gradle 코드젠으로 생성됨 — 직접 수정 금지. */
public abstract class ${m.flowName}Base {"""
    }

    private static String renderFieldsAndConstructor(FlowModel m) {
        def actionsArg = m.llmActions.collect { '"' + it + '"' }.join(', ')
        """\
    private static final Gson GSON = new Gson();

    private static final PipelineRunnerFactory FLOW_FACTORY =
        ServiceLoader.load(PipelineRunnerFactory.class).findFirst().orElseThrow();

    protected static final PipelineDefinition DEFINITION =
        FLOW_FACTORY.loadDefinition("${m.yamlResourcePath}");

    private static final PromptRenderer RENDERER =
        FLOW_FACTORY.classpathRenderer(${actionsArg});

    private final Executor executor;
    private final LlmExecutor llmExecutor;

    protected ${m.flowName}Base(Executor executor, LlmExecutor llmExecutor) {
        this.executor = executor;
        this.llmExecutor = llmExecutor;
    }"""
    }

    // ── public methods ─────────────────────────────────────────────────────

    private static String renderPublicMethods(FlowModel m) {
        String criticalCheck = renderCriticalCheck(m)
        String typedRun = renderTypedRun(m)
        """\
${typedRun}

    /** 크리티컬 노드 실패 여부를 반환한다. */
    public final boolean isCriticalFailure(${m.outputJavaType} result) {
        ${criticalCheck}
    }

    // ── internal: typed run의 단일 종착지 ─────────────────────────────────

    /**
     * Map 기반 직접 호출이 필요한 케이스(분석처럼 prereq를 동적으로 빌드)용 진입점.
     * 일반 호출자는 typed run()을 쓴다.
     */
    public final ${m.outputJavaType} runInternal(Map<String, Object> inputs, UUID sessionId) {
        ServerNodeExecutor serverNodes = buildServerNodeExecutor();
        PipelineRunner runner = FLOW_FACTORY.assemble(
            llmExecutor, RENDERER, serverNodes, buildFieldExtractor(),
            ExecutionListener.noop(), executor);
        PipelineResult raw = runner.run(
            DEFINITION, sessionId.toString(), DEFINITION.nodeIds(),
            ExecutionConfig.defaults(), null, inputs).join();
        return parseResult(raw);
    }"""
    }

    private static String renderPublicMethodsForRunner(FlowModel m) {
        if (m.serverNodes.isEmpty()) return renderPublicMethods(m)

        def snType = "${m.flowName}ServerNodes"
        String criticalCheck = renderCriticalCheck(m)
        String typedRun = renderTypedRunForRunner(m)
        """\
${typedRun}

    /** 크리티컬 노드 실패 여부를 반환한다. */
    public final boolean isCriticalFailure(${m.outputJavaType} result) {
        ${criticalCheck}
    }

    // ── internal: typed run의 단일 종착지 ─────────────────────────────────

    /**
     * Map 기반 직접 호출이 필요한 케이스(분석처럼 prereq를 동적으로 빌드)용 진입점.
     * 일반 호출자는 typed run()을 쓴다.
     */
    public final ${m.outputJavaType} runInternal(Map<String, Object> inputs, UUID sessionId, ${snType} serverNodes) {
        ServerNodeExecutor sne = buildServerNodeExecutor(serverNodes);
        PipelineRunner runner = FLOW_FACTORY.assemble(
            llmExecutor, RENDERER, sne, buildFieldExtractor(),
            ExecutionListener.noop(), executor);
        PipelineResult raw = runner.run(
            DEFINITION, sessionId.toString(), DEFINITION.nodeIds(),
            ExecutionConfig.defaults(), null, inputs).join();
        return parseResult(raw);
    }"""
    }

    private static String renderTypedRunForRunner(FlowModel m) {
        def snType = "${m.flowName}ServerNodes"
        def sessionIdPrereq = m.prerequisites.find { it.name == 'sessionId' }
        def userPrereqs = m.prerequisites.findAll { it.name != 'sessionId' }

        if (userPrereqs.isEmpty() && !sessionIdPrereq) {
            return """\
    /** 외부 입력 없는 파이프라인. */
    public final ${m.outputJavaType} run(UUID sessionId, ${snType} serverNodes) {
        return runInternal(java.util.Map.of(), sessionId, serverNodes);
    }"""
        }

        def allEntries = []
        if (sessionIdPrereq) {
            allEntries << '            Map.entry("sessionId", (Object) sessionId)'
        }
        userPrereqs.each { p ->
            allEntries << '            Map.entry("' + p.name + '", ' + mapEntryValue(p) + ')'
        }
        def mapEntries = allEntries.join(',\n')

        if (userPrereqs.isEmpty()) {
            return """\
    /** 파이프라인 실행. sessionId는 UUID에서 자동 주입. */
    public final ${m.outputJavaType} run(UUID sessionId, ${snType} serverNodes) {
        Map<String, Object> inputs = Map.ofEntries(
${mapEntries});
        return runInternal(inputs, sessionId, serverNodes);
    }"""
        }

        def params = userPrereqs.collect { p -> "        ${javaParamType(p.fqcn)} ${p.name}" }.join(',\n')
        """\
    /**
     * 파이프라인 실행. yaml prerequisites가 typed 인자로 풀려있다.
     */
    public final ${m.outputJavaType} run(
        UUID sessionId,
${params},
        ${snType} serverNodes) {
        Map<String, Object> inputs = Map.ofEntries(
${mapEntries});
        return runInternal(inputs, sessionId, serverNodes);
    }"""
    }

    /**
     * yaml prerequisites 리스트를 풀어 typed 메서드 파라미터로 emit한다.
     *
     * <p>{@code sessionId} prereq는 UUID 인자로 고정 — 나머지는 선언 타입(UUID, boolean, String 등)
     * 그대로 파라미터가 된다. Map&lt;String,Object&gt; inputs를 구성해 runInternal에 넘긴다.
     */
    private static String renderTypedRun(FlowModel m) {
        def sessionIdPrereq = m.prerequisites.find { it.name == 'sessionId' }
        def userPrereqs = m.prerequisites.findAll { it.name != 'sessionId' }

        if (userPrereqs.isEmpty() && !sessionIdPrereq) {
            return """\
    /** 외부 입력 없는 파이프라인. */
    public final ${m.outputJavaType} run(UUID sessionId) {
        return runInternal(java.util.Map.of(), sessionId);
    }"""
        }

        def allEntries = []
        if (sessionIdPrereq) {
            allEntries << '            Map.entry("sessionId", (Object) sessionId)'
        }
        userPrereqs.each { p ->
            allEntries << '            Map.entry("' + p.name + '", ' + mapEntryValue(p) + ')'
        }
        def mapEntries = allEntries.join(',\n')

        if (userPrereqs.isEmpty()) {
            return """\
    /** 파이프라인 실행. sessionId는 UUID에서 자동 주입. */
    public final ${m.outputJavaType} run(UUID sessionId) {
        Map<String, Object> inputs = Map.ofEntries(
${mapEntries});
        return runInternal(inputs, sessionId);
    }"""
        }

        def params = userPrereqs.collect { p -> "        ${javaParamType(p.fqcn)} ${p.name}" }.join(',\n')
        """\
    /**
     * 파이프라인 실행. yaml prerequisites가 typed 인자로 풀려있다.
     */
    public final ${m.outputJavaType} run(
        UUID sessionId,
${params}) {
        Map<String, Object> inputs = Map.ofEntries(
${mapEntries});
        return runInternal(inputs, sessionId);
    }

    /** run(Input) 오버로드 — typed Input record로 호출. */
    public final ${m.outputJavaType} run(${m.flowName}Input input) {
        return run(${(['input.sessionId()'] + userPrereqs.collect { 'input.' + it.name + '()' }).join(', ')});
    }"""
    }

    /** prereq를 Map<String,Object> entry 값으로 변환하는 표현식. 엔진 내부에서 직렬화하므로 변환 없음. */
    private static String mapEntryValue(FlowModel.Prerequisite p) {
        return "(Object) ${p.name}"
    }

    /** fqcn → 생성 코드에서 쓸 Java 파라미터 타입 표현 (boolean, UUID, String 등). */
    private static String javaParamType(String fqcn) {
        switch (fqcn) {
            case 'boolean':
            case 'java.lang.Boolean': return 'boolean'
            case 'int':
            case 'java.lang.Integer': return 'int'
            case 'long':
            case 'java.lang.Long':    return 'long'
            case 'java.util.UUID':    return 'UUID'
            case 'java.lang.String':  return 'String'
            default:
                def parts = fqcn.split('\\.')
                return parts.last()
        }
    }

    private static String renderCriticalCheck(FlowModel m) {
        if (m.singleResult) {
            // 단일 결과: result 자체가 null이면 critical 실패
            return m.resultNodes[0].critical
                ? "return result == null;"
                : "return false;"
        }
        def checks = m.resultNodes.findAll { it.critical }
            .collect { "if (result.${it.field}() == null) return true;" }
        if (checks.isEmpty()) return "return false;"
        "${checks.join('\n        ')}\n        return false;"
    }

    // ── user overrides (server nodes + hooks) ──────────────────────────────

    private static String renderUserOverrides(FlowModel m) {
        def hookMethods = m.resultNodes.findAll { it.hook }.collect { node ->
            "    protected void on${node.field.capitalize()}(${node.javaType} result) {}"
        }.join('\n\n')

        def abstractMethods = [
            m.scatterNodes.collect { abstractServerSig(it, 'Scatter', 'java.util.List<String>', 'java.util.Map<String, Object> inputs') },
            m.gatherNodes.collect { abstractServerSig(it, 'Gather', 'String', 'java.util.List<String> chunks') },
            m.transformNodes.collect { node ->
                def returnType = node.typed
                    ? 'com.returney.flow.domain.execution.NodeOutput'
                    : 'String'
                abstractServerSig(node, 'Transform', returnType, 'java.util.Map<String, Object> inputs')
            }
        ].flatten().join('\n\n')

        hookMethods ? "${hookMethods}\n\n${abstractMethods}" : abstractMethods
    }

    // ── internals (FieldExtractor + ServerNodeExecutor + parseResult) ─────

    private static String renderInternals(FlowModel m) {
        def supportedIds = m.serverNodes.collect { '"' + it.id + '"' }.join(', ')
        def scatterCases = switchOrThrow(
            m.scatterNodes.collect { serverDispatchCase(it, 'Scatter', 'inputs') }, 'scatter')
        def gatherCases = switchOrThrow(
            m.gatherNodes.collect { serverDispatchCase(it, 'Gather', 'chunks') }, 'gather')
        def transformCases = switchOrThrow(
            m.transformNodes.collect { node ->
                def methodName = node.methodName.replace('Transform', '') + 'Transform'
                node.typed
                    ? "                case \"${node.id}\" -> ${methodName}(inputs).prompt();"
                    : "                case \"${node.id}\" -> ${methodName}(inputs);"
            }, 'transform')
        def transformTypedCases = switchOrThrow(
            m.transformNodes.collect { node ->
                def methodName = node.methodName.replace('Transform', '') + 'Transform'
                node.typed
                    ? "                case \"${node.id}\" -> ${methodName}(inputs);"
                    : "                case \"${node.id}\" -> com.returney.flow.domain.execution.NodeOutput.textOnly(${methodName}(inputs));"
            }, 'transformTyped')

        def fieldExtractorBody = renderFieldExtractorBody(m)

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

        def returnStmt
        if (m.singleResult) {
            returnStmt = "        return ${m.resultNodes[0].field};"
        } else {
            def args = m.resultNodes.collect { it.field }.join(', ')
            returnStmt = "        return new ${m.outputJavaType}(${args});"
        }

        """\
    // ── internal ─────────────────────────────────────────────────────────────

    private NodeOutputExtractor buildFieldExtractor() {
        return new NodeOutputExtractor() {
            @Override
            public String extract(String nodeId, String fieldName, String output) {
                ${fieldExtractorBody}
            }
        };
    }

    private ServerNodeExecutor buildServerNodeExecutor() {
        return new ServerNodeExecutor() {
            @Override
            public boolean supports(String nodeId) {
                return java.util.Set.of(${supportedIds}).contains(nodeId);
            }
            @Override
            public List<String> scatter(String nodeId, Map<String, Object> inputs) {
                ${scatterCases}
            }
            @Override
            public String gather(String nodeId, List<String> chunks) {
                ${gatherCases}
            }
            @Override
            public String transform(String nodeId, Map<String, Object> inputs) {
                ${transformCases}
            }
            @Override
            public com.returney.flow.domain.execution.NodeOutput transformTyped(String nodeId, Map<String, Object> inputs) {
                ${transformTypedCases}
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T extractTyped(
            com.returney.flow.domain.execution.NodeResult node, Class<T> type) {
        if (node != null && node.typedOutput() != null) return type.cast(node.typedOutput());
        String raw = node != null ? node.output() : null;
        return GSON.fromJson(LlmJsonExtractor.extract(raw), type);
    }

    private ${m.outputJavaType} parseResult(PipelineResult result) {
${parseBlocks}

${returnStmt}
    }
}
"""
    }

    private static String renderFieldExtractorBody(FlowModel m) {
        if (m.fieldRefs.isEmpty()) {
            return """throw new IllegalArgumentException(
                    "Unknown field reference: " + nodeId + "." + fieldName);"""
        }
        def cases = m.fieldRefs.collect { ref ->
            """\
                    case "${ref.nodeId}.${ref.fieldName}" ->
                        GSON.fromJson(LlmJsonExtractor.extract(output), ${ref.javaType}.class).${ref.accessorName}();"""
        }.join('\n')
        """return switch (nodeId + "." + fieldName) {
${cases}
                    default -> throw new IllegalArgumentException(
                        "Unknown field reference: " + nodeId + "." + fieldName);
                };"""
    }

    private static String abstractServerSig(FlowModel.ServerNode node, String suffix, String returnType, String params) {
        def methodName = node.methodName.replace(suffix, '') + suffix
        "    protected abstract ${returnType} ${methodName}(${params});"
    }

    private static String serverDispatchCase(FlowModel.ServerNode node, String suffix, String argName) {
        def methodName = node.methodName.replace(suffix, '') + suffix
        """                case "${node.id}" -> ${methodName}(${argName});"""
    }

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

    // ── Runner-specific renders ────────────────────────────────────────────

    private static String renderHeaderForRunner(FlowModel m) {
        def prereqImports = m.prerequisites
            .findAll { it.fqcn != 'java.lang.String' && !it.fqcn.startsWith('java.lang.')
                && !it.fqcn.startsWith('boolean') && !it.fqcn.startsWith('int')
                && !it.fqcn.startsWith('long') && it.fqcn != 'java.util.UUID' }
            .collect { "import ${it.fqcn};" }
        def importLines = prereqImports + [m.outputImportLine] +
            m.resultNodes.collect { it.importLine } +
            m.fieldRefs.collect { it.importLine }
        def importBlock = importsBlock(importLines.findAll { it })
        """\
package ${m.pkg};

import com.google.gson.Gson;
import com.returney.flow.domain.definition.PipelineDefinition;
import com.returney.flow.domain.execution.ExecutionConfig;
import com.returney.flow.domain.execution.PipelineResult;
import com.returney.flow.port.ExecutionListener;
import com.returney.flow.port.LlmExecutor;
import com.returney.flow.port.NodeOutputExtractor;
import com.returney.flow.port.PipelineRunner;
import com.returney.flow.port.PipelineRunnerFactory;
import com.returney.flow.port.PromptRenderer;
import com.returney.flow.port.ServerNodeExecutor;
import com.returney.flow.util.LlmJsonExtractor;
import java.util.ServiceLoader;${importBlock}
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Gradle 코드젠으로 생성됨 — 직접 수정 금지. */
public class ${m.flowName}Runner {"""
    }

    private static String renderFieldsAndConstructorForRunner(FlowModel m) {
        def actionsArg = m.llmActions.collect { '"' + it + '"' }.join(', ')
        """\
    private static final Gson GSON = new Gson();

    private static final PipelineRunnerFactory FLOW_FACTORY =
        ServiceLoader.load(PipelineRunnerFactory.class).findFirst().orElseThrow();

    protected static final PipelineDefinition DEFINITION =
        FLOW_FACTORY.loadDefinition("${m.yamlResourcePath}");

    private static final PromptRenderer RENDERER =
        FLOW_FACTORY.classpathRenderer(${actionsArg});

    private final Executor executor;
    private final LlmExecutor llmExecutor;

    public ${m.flowName}Runner(
        Executor executor,
        LlmExecutor llmExecutor) {
        this.executor = executor;
        this.llmExecutor = llmExecutor;
    }"""
    }

    private static String renderInternalsForRunner(FlowModel m) {
        def hasServerNodes = !m.serverNodes.isEmpty()
        def snParam = hasServerNodes ? "${m.flowName}ServerNodes serverNodes" : ""
        def supportedIds = m.serverNodes.collect { '"' + it.id + '"' }.join(', ')
        def scatterCases = switchOrThrow(
            m.scatterNodes.collect { serverDispatchCaseWithPrefix(it, 'Scatter', 'inputs', 'serverNodes') }, 'scatter')
        def gatherCases = switchOrThrow(
            m.gatherNodes.collect { serverDispatchCaseWithPrefix(it, 'Gather', 'chunks', 'serverNodes') }, 'gather')
        def transformCases = switchOrThrow(
            m.transformNodes.collect { node ->
                def methodName = node.methodName.replace('Transform', '') + 'Transform'
                node.typed
                    ? "                case \"${node.id}\" -> serverNodes.${methodName}(inputs).prompt();"
                    : "                case \"${node.id}\" -> serverNodes.${methodName}(inputs);"
            }, 'transform')
        def transformTypedCases = switchOrThrow(
            m.transformNodes.collect { node ->
                def methodName = node.methodName.replace('Transform', '') + 'Transform'
                node.typed
                    ? "                case \"${node.id}\" -> serverNodes.${methodName}(inputs);"
                    : "                case \"${node.id}\" -> com.returney.flow.domain.execution.NodeOutput.textOnly(serverNodes.${methodName}(inputs));"
            }, 'transformTyped')

        def fieldExtractorBody = renderFieldExtractorBody(m)

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

        def returnStmt
        if (m.singleResult) {
            returnStmt = "        return ${m.resultNodes[0].field};"
        } else {
            def args = m.resultNodes.collect { it.field }.join(', ')
            returnStmt = "        return new ${m.outputJavaType}(${args});"
        }

        """\
    // ── internal ─────────────────────────────────────────────────────────────

    private NodeOutputExtractor buildFieldExtractor() {
        return new NodeOutputExtractor() {
            @Override
            public String extract(String nodeId, String fieldName, String output) {
                ${fieldExtractorBody}
            }
        };
    }

    private ServerNodeExecutor buildServerNodeExecutor(${snParam}) {
        return new ServerNodeExecutor() {
            @Override
            public boolean supports(String nodeId) {
                return java.util.Set.of(${supportedIds}).contains(nodeId);
            }
            @Override
            public List<String> scatter(String nodeId, Map<String, Object> inputs) {
                ${scatterCases}
            }
            @Override
            public String gather(String nodeId, List<String> chunks) {
                ${gatherCases}
            }
            @Override
            public String transform(String nodeId, Map<String, Object> inputs) {
                ${transformCases}
            }
            @Override
            public com.returney.flow.domain.execution.NodeOutput transformTyped(String nodeId, Map<String, Object> inputs) {
                ${transformTypedCases}
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T extractTyped(
            com.returney.flow.domain.execution.NodeResult node, Class<T> type) {
        if (node != null && node.typedOutput() != null) return type.cast(node.typedOutput());
        String raw = node != null ? node.output() : null;
        return GSON.fromJson(LlmJsonExtractor.extract(raw), type);
    }

    private ${m.outputJavaType} parseResult(PipelineResult result) {
${parseBlocks}

${returnStmt}
    }
}
"""
    }

    private static String serverDispatchCaseWithPrefix(FlowModel.ServerNode node, String suffix, String argName, String prefix) {
        def methodName = node.methodName.replace(suffix, '') + suffix
        """                case "${node.id}" -> ${prefix}.${methodName}(${argName});"""
    }
}
