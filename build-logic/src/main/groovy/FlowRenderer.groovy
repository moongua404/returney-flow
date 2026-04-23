class FlowRenderer {

    static String prerequisites(FlowModel m) {
        def fields = m.prerequisites.collect { "    String ${it}" }.join(',\n')
        """\
package ${m.pkg};

/** 파이프라인 외부 입력 계약. Gradle 코드젠으로 생성됨 — 직접 수정 금지. */
public record ${m.flowName}Prerequisites(
${fields}
) {
    /** Map에서 Prerequisites를 생성한다. */
    public static ${m.flowName}Prerequisites from(java.util.Map<String, String> map) {
        return new ${m.flowName}Prerequisites(
${m.prerequisites.collect { '            map.get("' + it + '")' }.join(',\n')}
        );
    }
}
"""
    }

    static String serverNodesInterface(FlowModel m) {
        def methods = m.serverNodes.collect { it.methodSignature }.join('\n\n')

        """\
package ${m.pkg};

/** 서버 커스텀 노드 구현 인터페이스. Gradle 코드젠으로 생성됨 — 직접 수정 금지. */
public interface ${m.flowName}ServerNodes {

${methods}
}
"""
    }

    static String serverNodeExecutor(FlowModel m) {
        def supportedIds = m.serverNodes.collect { '"' + it.id + '"' }.join(', ')

        def scatterBody   = switchOrThrow(
            m.scatterNodes.collect { """            case "${it.id}" -> delegate.${it.methodName}(inputs);""" },
            "scatter")
        def gatherBody    = switchOrThrow(
            m.gatherNodes.collect { """            case "${it.id}" -> delegate.${it.methodName}(chunks);""" },
            "gather")
        def transformBody = switchOrThrow(
            m.transformNodes.collect { """            case "${it.id}" -> delegate.${it.methodName}(inputs);""" },
            "transform")

        """\
package ${m.pkg};

import com.returney.flow.port.ServerNodeExecutor;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Gradle 코드젠으로 생성됨 — 직접 수정 금지. */
public class ${m.flowName}ServerNodeExecutor implements ServerNodeExecutor {

    private static final Set<String> SUPPORTED = Set.of(${supportedIds});

    private final ${m.flowName}ServerNodes delegate;

    public ${m.flowName}ServerNodeExecutor(${m.flowName}ServerNodes delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean supports(String nodeId) {
        return SUPPORTED.contains(nodeId);
    }

    @Override
    public List<String> scatter(String nodeId, Map<String, String> inputs) {
        ${scatterBody}
    }

    @Override
    public String gather(String nodeId, List<String> chunks) {
        ${gatherBody}
    }

    @Override
    public String transform(String nodeId, Map<String, String> inputs) {
        ${transformBody}
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

    static String flowInterface(FlowModel m) {
        """\
package ${m.pkg};

import com.returney.flow.domain.execution.PipelineResult;

/** Gradle 코드젠으로 생성됨 — 직접 수정 금지. */
public interface ${m.flowName}Flow {
    ${m.flowName}Result parse(PipelineResult result);
    boolean isCriticalFailure(PipelineResult result);
}
"""
    }

    static String flowImpl(FlowModel m) {
        def importBlock  = importsBlock(m.resultNodes.collect { it.importLine }.findAll { it })
        def importSuffix = importBlock ?: ""

        def parseBlocks = m.resultNodes.collect { node ->
            """\
        ${node.javaType} ${node.field} = null;
        if (!result.failedNodes().contains("${node.id}") && result.nodeResults().containsKey("${node.id}")) {
            ${node.field} = ${node.parseExpr};
        }"""
        }.join('\n\n')

        def criticalSet = m.resultNodes.findAll { it.critical }.collect { '"' + it.id + '"' }.join(', ')
        def resultArgs  = m.resultNodes.collect { it.field }.join(', ')

        """\
package ${m.pkg};

import com.google.gson.Gson;
import com.returney.flow.domain.execution.PipelineResult;${importSuffix}

/** Gradle 코드젠으로 생성됨 — 직접 수정 금지. */
public class ${m.flowName}FlowImpl implements ${m.flowName}Flow {

    private static final Gson GSON = new Gson();

    @Override
    public ${m.flowName}Result parse(PipelineResult result) {
${parseBlocks}

        return new ${m.flowName}Result(${resultArgs});
    }

    @Override
    public boolean isCriticalFailure(PipelineResult result) {
        var critical = java.util.Set.of(${criticalSet});
        return result.failedNodes().stream().anyMatch(critical::contains);
    }
}
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
