class FlowModel {

    final String flowName
    final String pkg
    final List<String> prerequisites
    final List<ResultNode> resultNodes
    final List<ServerNode> serverNodes
    final List<ServerNode> scatterNodes
    final List<ServerNode> gatherNodes
    final List<ServerNode> transformNodes
    final List<FieldRef> fieldRefs

    private FlowModel(String flowName, String pkg,
                      List<String> prerequisites,
                      List<ResultNode> resultNodes,
                      List<ServerNode> serverNodes,
                      List<FieldRef> fieldRefs) {
        this.flowName       = flowName
        this.pkg            = pkg
        this.prerequisites  = prerequisites
        this.resultNodes    = resultNodes
        this.serverNodes    = serverNodes
        this.scatterNodes   = serverNodes.findAll { it.type == 'scatter' }
        this.gatherNodes    = serverNodes.findAll { it.type == 'gather' }
        this.transformNodes = serverNodes.findAll { it.type == 'transform' }
        this.fieldRefs      = fieldRefs
    }

    static FlowModel from(Map pipeline, String pkg) {
        def flowName = toCamelCase(pipeline.name as String)
        def allNodes = pipeline.nodes as List<Map>
        def prereqs  = (pipeline.prerequisites as List<String>) ?: []

        def resultNodes = allNodes
            .findAll { it?.result?.type != null }
            .collect { buildResultNode(it) }

        def serverTypes = ['scatter', 'gather', 'transform'] as Set
        def serverNodes = allNodes
            .findAll { serverTypes.contains((it.type as String)?.toLowerCase()) }
            .collect { buildServerNode(it) }

        def fieldRefs = buildFieldRefs(allNodes)

        new FlowModel(flowName, pkg, prereqs, resultNodes, serverNodes, fieldRefs)
    }

    // ── inner model classes ───────────────────────────────────────────────────

    static class ResultNode {
        String id
        String field       // camelField(id)
        String fqcn        // fully-qualified class name
        String javaType    // simple name for generated code
        String importLine  // "import Foo;" or null for builtins
        boolean builtin
        boolean critical
        String parseExpr   // Java expression: parses output() into javaType
    }

    static class ServerNode {
        String id
        String type           // "scatter" | "gather" | "transform"
        String methodName     // camelField(id) + type.capitalize()
        String methodSignature // full interface method declaration line
    }

    static class FieldRef {
        String nodeId
        String fieldName     // as written in YAML
        String accessorName  // camelField(fieldName) — Java record accessor
        String javaType      // simple type of the upstream node's result
        String importLine    // or null
    }

    // ── builders ─────────────────────────────────────────────────────────────

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
        def rawAccess = "result.nodeResults().get(\"${n.id}\").output()"
        n.parseExpr  = builtin
            ? castBuiltin(fqcn, rawAccess)
            : "GSON.fromJson(${rawAccess}, ${n.javaType}.class)"
        n
    }

    private static ServerNode buildServerNode(Map node) {
        def n = new ServerNode()
        n.id         = node.id as String
        n.type       = (node.type as String).toLowerCase()
        n.methodName = camelField(n.id) + n.type.capitalize()
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
