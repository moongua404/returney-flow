class FlowModel {

    final String flowName
    final String pkg
    final String yamlResourcePath
    final List<Prerequisite> prerequisites
    final List<ResultNode> resultNodes
    final List<ServerNode> serverNodes
    final List<ServerNode> scatterNodes
    final List<ServerNode> gatherNodes
    final List<ServerNode> transformNodes
    final List<FieldRef> fieldRefs
    final List<String> llmActions

    /**
     * run() 반환 타입의 FQCN. 결정 규칙:
     * <ul>
     *   <li>yaml top-level {@code output.type:} 가 있으면 그 FQCN — 도메인 record를 호출자가
     *       손으로 정의한 경우 (예: 다중 결과 AnalysisOutcome)</li>
     *   <li>없고 result-bearing 노드가 정확히 1개면 그 노드의 result.type — 단일 결과 케이스</li>
     *   <li>그 외엔 검증 실패 (FlowValidator에서 잡음)</li>
     * </ul>
     */
    final String outputFqcn
    final String outputJavaType    // simple type for generated code
    final String outputImportLine  // "import Foo;" or null for builtin/same-package
    final boolean outputBuiltin    // true면 단일 result, primitive/String 등
    final boolean singleResult     // result-bearing 노드 1개 케이스 (래핑 안 하고 직접 반환)

    private FlowModel(String flowName, String pkg, String yamlResourcePath,
                      List<String> prerequisites,
                      List<ResultNode> resultNodes,
                      List<ServerNode> serverNodes,
                      List<FieldRef> fieldRefs,
                      List<String> llmActions,
                      String outputFqcn, String outputJavaType,
                      String outputImportLine, boolean outputBuiltin,
                      boolean singleResult) {
        this.flowName          = flowName
        this.pkg               = pkg
        this.yamlResourcePath  = yamlResourcePath
        this.prerequisites     = prerequisites
        this.resultNodes       = resultNodes
        this.serverNodes       = serverNodes
        this.scatterNodes      = serverNodes.findAll { it.type == 'scatter' }
        this.gatherNodes       = serverNodes.findAll { it.type == 'gather' }
        this.transformNodes    = serverNodes.findAll { it.type == 'transform' }
        this.fieldRefs         = fieldRefs
        this.llmActions        = llmActions
        this.outputFqcn        = outputFqcn
        this.outputJavaType    = outputJavaType
        this.outputImportLine  = outputImportLine
        this.outputBuiltin     = outputBuiltin
        this.singleResult      = singleResult
    }

    static FlowModel from(Map pipeline, String pkg) {
        return from(pipeline, pkg, 'pipeline-flow.yaml')
    }

    static FlowModel from(Map pipeline, String pkg, String yamlResourcePath) {
        def flowName = toCamelCase(pipeline.name as String)
        def allNodes = pipeline.nodes as List<Map>
        def prereqs  = buildPrerequisites(pipeline.prerequisites as List)

        def resultNodes = allNodes
            .findAll { it?.result?.type != null }
            .collect { buildResultNode(it) }

        def serverTypes = ['scatter', 'gather', 'transform'] as Set
        def resultNodesById = resultNodes.collectEntries { [(it.id): it] }
        def serverNodes = allNodes
            .findAll { serverTypes.contains((it.type as String)?.toLowerCase()) }
            .collect { buildServerNode(it, resultNodesById) }

        def fieldRefs = buildFieldRefs(allNodes)

        def llmTypes = ['llm', 'template'] as Set
        def llmActions = allNodes
            .findAll { llmTypes.contains((it.type as String)?.toLowerCase()) }
            .collect { ((it.action ?: it.id) as String) }
            .unique()

        // ── output type 해석 ─────────────────────────────────────────────────
        // 모델 단계에선 lenient — 결정만 하고 throw 안 함 (FlowValidator/Renderer에서 강제).
        // - yaml output.type 있으면 그걸로 (다중 결과 케이스, 호출자가 도메인 record 직접 정의)
        // - 없고 단일 result-bearing 노드면 그 노드의 result.type
        // - 그 외 (0개 또는 2+개인데 output.type 미선언)는 java.lang.Void 폴백
        String outputFqcn
        boolean singleResult
        Map outputCfg = pipeline.output as Map
        if (outputCfg?.type != null) {
            outputFqcn = resolveType(outputCfg.type as String)
            singleResult = false
        } else if (resultNodes.size() == 1) {
            outputFqcn = resultNodes[0].fqcn
            singleResult = true
        } else {
            outputFqcn = 'java.lang.Void'
            singleResult = false
        }

        boolean outputBuiltin = isBuiltinType(outputFqcn)
        String outputJavaType = outputBuiltin ? simpleBuiltin(outputFqcn) : simpleType(outputFqcn)
        String outputImportLine = outputBuiltin ? null : "import ${outerImport(outputFqcn)};"

        new FlowModel(flowName, pkg, yamlResourcePath,
            prereqs, resultNodes, serverNodes, fieldRefs, llmActions,
            outputFqcn, outputJavaType, outputImportLine, outputBuiltin, singleResult)
    }

    // ── inner model classes ───────────────────────────────────────────────────

    static class Prerequisite {
        String name
        String fqcn   // "java.util.UUID", "boolean", "java.lang.String" 등
    }

    static class ResultNode {
        String id
        String field       // camelField(id)
        String fqcn        // fully-qualified class name
        String javaType    // simple name for generated code
        String importLine  // "import Foo;" or null for builtins
        boolean builtin
        boolean critical
        boolean hook
        String parseExpr   // Java expression: parses output() into javaType
    }

    static class ServerNode {
        String id
        String type           // "scatter" | "gather" | "transform"
        String methodName     // camelField(id) + type.capitalize()
        String methodSignature // full interface method declaration line
        boolean typed         // transform 노드가 result.type 도 갖고 있으면 true — NodeOutput 리턴
        String typedJavaType  // typed 일 때 도메인 객체 타입 (사용처 안내용)
    }

    static class FieldRef {
        String nodeId
        String fieldName     // as written in YAML
        String accessorName  // camelField(fieldName) — Java record accessor
        String javaType      // simple type of the upstream node's result
        String importLine    // or null
    }

    // ── builders ─────────────────────────────────────────────────────────────

    private static List<Prerequisite> buildPrerequisites(List items) {
        if (!items) return []
        items.collect { item ->
            if (item instanceof String) {
                throw new IllegalArgumentException(
                    "[FlowCodegen] Prerequisite '${item}' must declare explicit type. " +
                    "Use '- name: ${item}\n  type: java.lang.String' instead of '- ${item}'")
            }
            def m = item as Map
            def name = m.name as String
            if (!m.type) {
                throw new IllegalArgumentException(
                    "[FlowCodegen] Prerequisite '${name}' is missing required 'type:' field.")
            }
            def p = new Prerequisite()
            p.name = name
            p.fqcn = resolveType(m.type as String)
            p
        }
    }

    private static ResultNode buildResultNode(Map node) {
        def fqcn    = resolveType(node.result.type as String)
        def builtin = isBuiltinType(fqcn)
        def n = new ResultNode()
        n.id         = node.id as String
        n.field      = camelField(n.id)
        n.fqcn       = fqcn
        n.javaType   = builtin ? simpleBuiltin(fqcn) : simpleType(fqcn)
        n.importLine = builtin ? null : "import ${outerImport(fqcn)};"
        n.builtin    = builtin
        n.critical   = node.critical as boolean
        n.hook       = node.hook as boolean
        def rawNodeAccess = "result.nodeResults().get(\"${n.id}\")"
        def rawAccess = "${rawNodeAccess}.output()"
        // typedOutput 우선 사용 — 프레임워크가 이미 역직렬화했으면 그대로 캐스트.
        // 없으면 Gson 폴백 (LlmJsonExtractor로 코드펜스 제거 후 파싱).
        n.parseExpr  = builtin
            ? castBuiltin(fqcn, rawAccess)
            : "extractTyped(${rawNodeAccess}, ${n.javaType}.class)"
        n
    }

    private static ServerNode buildServerNode(Map node, Map<String, ResultNode> resultNodesById) {
        def n = new ServerNode()
        n.id         = node.id as String
        n.type       = (node.type as String).toLowerCase()
        n.methodName = camelField(n.id) + n.type.capitalize()
        def matchingResult = resultNodesById[n.id]
        n.typed = (n.type == 'transform' && matchingResult != null && !matchingResult.builtin)
        n.typedJavaType = n.typed ? matchingResult.javaType : null
        n.methodSignature =
            n.type == 'scatter'   ? "    java.util.List<String> ${n.methodName}(java.util.Map<String, String> inputs);"
          : n.type == 'gather'    ? "    String ${n.methodName}(java.util.List<String> chunks);"
          : n.type == 'transform' ? "    String ${n.methodName}(java.util.Map<String, String> inputs);"
          : ""
        n
    }

    private static List<FieldRef> buildFieldRefs(List<Map> allNodes) {
        def typeMap = allNodes.collectEntries { [(it.id as String): resolveType(it.result?.type as String ?: '')] }
        def prefix  = 'Prerequisites.'
        def refs    = []

        allNodes.each { node ->
            ((node.inputs as Map)?.values() ?: []).each { spec ->
                def s = spec as String
                if (!s.startsWith(prefix) && s.contains('.')) {
                    def dot        = s.indexOf('.')
                    def nodeId     = s.substring(0, dot)
                    def rawField   = s.substring(dot + 1)
                    def fqcn       = typeMap[nodeId] ?: ''

                    if (isBuiltinType(fqcn)) {
                        throw new IllegalArgumentException(
                            "[FlowCodegen] Cannot use dot-access on builtin type '${fqcn}' for node '${nodeId}'")
                    }

                    def ref = new FieldRef()
                    ref.nodeId       = nodeId
                    ref.fieldName    = rawField
                    ref.accessorName = camelField(rawField)
                    ref.javaType     = fqcn ? simpleType(fqcn) : ''
                    ref.importLine   = (fqcn && !isBuiltinType(fqcn)) ? "import ${outerImport(fqcn)};" : null
                    refs << ref
                }
            }
        }

        refs.unique { it.nodeId + '.' + it.fieldName }
    }

    // ── type helpers ──────────────────────────────────────────────────────────

    static String toCamelCase(String hyphenated) {
        hyphenated.split('-').collect { it.capitalize() }.join('')
    }

    static String resolveType(String type) {
        switch (type) {
            case 'String':  return 'java.lang.String'
            case 'Integer': return 'java.lang.Integer'
            case 'Long':    return 'java.lang.Long'
            case 'Boolean': return 'java.lang.Boolean'
            case 'Double':  return 'java.lang.Double'
            default:        return type ?: ''
        }
    }

    static boolean isBuiltinType(String fqcn) {
        fqcn.startsWith('java.lang.') || fqcn.startsWith('java.util.')
    }

    static String simpleType(String fqcn) {
        def dotForm  = fqcn.replace('$', '.')
        def parts    = dotForm.split('\\.')
        def classIdx = parts.findIndexOf { it && Character.isUpperCase(it.charAt(0)) }
        parts[classIdx..-1].join('.')
    }

    // "java.lang.String" → "String", "java.util.List" → "java.util.List" (no import)
    static String simpleBuiltin(String fqcn) {
        fqcn.startsWith('java.lang.') ? fqcn.substring('java.lang.'.length()) : fqcn
    }

    static String outerImport(String fqcn) {
        def dotForm  = fqcn.replace('$', '.')
        def parts    = dotForm.split('\\.')
        def classIdx = parts.findIndexOf { it && Character.isUpperCase(it.charAt(0)) }
        parts[0..classIdx].join('.')
    }

    static String camelField(String snakeId) {
        def parts = snakeId.split('_')
        if (parts.length == 1) return parts[0]
        parts[0] + parts[1..-1].collect { it.capitalize() }.join('')
    }

    private static String castBuiltin(String fqcn, String expr) {
        switch (fqcn) {
            case 'java.lang.String':  return expr
            case 'java.lang.Integer': return "Integer.parseInt(${expr})"
            case 'java.lang.Long':    return "Long.parseLong(${expr})"
            case 'java.lang.Boolean': return "Boolean.parseBoolean(${expr})"
            case 'java.lang.Double':  return "Double.parseDouble(${expr})"
            default:                  return expr
        }
    }
}
