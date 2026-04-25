import org.yaml.snakeyaml.Yaml

class FlowValidator {

    private static final String PREREQ_PREFIX = 'Prerequisites.'
    private static final String SERVER_TYPES_REGEX = /^(scatter|gather|transform)$/
    // {{varName}} 형태만 매칭. {{key:section}} (콜론 포함)은 내부 분기 참조이므로 제외.
    private static final java.util.regex.Pattern VAR_PATTERN =
        java.util.regex.Pattern.compile(/\{\{([a-zA-Z_][a-zA-Z0-9_]*)\}\}/)

    /**
     * 모든 검증을 한 번에 실행.
     *
     * <p>두 단계로 나뉨:
     * <ul>
     *   <li>strict — 노드 ID 중복/입력 소스/순환/prerequisites 참조. 실패 시 빌드 중단.
     *   <li>lenient — prompts/*.yaml과의 정합성. 누락은 stderr 경고만 (빌드는 통과).
     *       소비자가 정합성을 정리한 뒤 -PstrictFlowValidation으로 strict 승격 가능.
     * </ul>
     */
    static void validateAll(Map pipeline, File promptsDir) {
        validateAll(pipeline, promptsDir, false)
    }

    static void validateAll(Map pipeline, File promptsDir, boolean strictPrompts) {
        def nodes = pipeline.nodes as List<Map>
        if (nodes == null) {
            throw new IllegalArgumentException("[FlowCodegen] pipeline missing 'nodes' section")
        }

        validateUniqueNodeIds(nodes)
        validateInputSources(nodes)
        validateNoCycles(nodes)
        validatePrerequisiteRefs(nodes,
            ((pipeline.prerequisites as List<String>) ?: []) as Set)

        if (promptsDir != null && promptsDir.exists()) {
            def actionToFile = scanPrompts(promptsDir)
            def warnings = []
            collectActionMappingIssues(nodes, actionToFile, warnings)
            collectPromptVariableIssues(nodes, actionToFile, warnings)
            if (!warnings.isEmpty()) {
                if (strictPrompts) {
                    throw new IllegalArgumentException(
                        "[FlowCodegen] prompts 정합성 오류:\n  - " + warnings.join('\n  - '))
                } else {
                    warnings.each { System.err.println("[FlowCodegen] WARN: ${it}") }
                }
            }
        }
    }

    /** 노드 ID 중복 검증. */
    static void validateUniqueNodeIds(List<Map> nodes) {
        def seen = new HashSet<String>()
        nodes.each { node ->
            String id = node.id as String
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("[FlowCodegen] node missing 'id'")
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("[FlowCodegen] Duplicate node id: ${id}")
            }
        }
    }

    /** inputs 소스 노드 존재 + 형식 검증 (Prerequisites.x 또는 nodeId / nodeId.field). */
    static void validateInputSources(List<Map> nodes) {
        def nodeIds = nodes.collect { it.id as String } as Set
        nodes.each { node ->
            def inputs = (node.inputs as Map) ?: [:]
            inputs.each { key, spec ->
                String s = spec as String
                if (s == null || s.isBlank()) {
                    throw new IllegalArgumentException(
                        "[FlowCodegen] Node '${node.id}' input '${key}' has empty source")
                }
                if (s.startsWith(PREREQ_PREFIX)) return // 별도 검증
                String upstream = s.contains('.') ? s.substring(0, s.indexOf('.')) : s
                if (!nodeIds.contains(upstream)) {
                    throw new IllegalArgumentException(
                        "[FlowCodegen] Node '${node.id}' input '${key}' references " +
                            "unknown upstream node: '${upstream}'")
                }
            }
        }
    }

    /** Prerequisites.xxx 참조가 실제 선언된 prerequisite인지 확인한다. */
    static void validatePrerequisiteRefs(List<Map> nodes, Set<String> declared) {
        nodes.each { node ->
            ((node.inputs as Map)?.values() ?: []).each { spec ->
                if ((spec as String).startsWith(PREREQ_PREFIX)) {
                    def name = (spec as String).substring(PREREQ_PREFIX.length())
                    if (!declared.contains(name)) {
                        throw new IllegalArgumentException(
                            "[FlowCodegen] Node '${node.id}' references undeclared prerequisite: ${name}")
                    }
                }
            }
        }
    }

    /** DAG 순환 검증 (Kahn's algorithm). */
    static void validateNoCycles(List<Map> nodes) {
        def adjacency = [:].withDefault { [] as List }   // upstream → [downstreams]
        def indegree = [:].withDefault { 0 }              // node → indegree

        nodes.each { node ->
            String id = node.id as String
            indegree[id] = indegree[id] ?: 0
            ((node.inputs as Map)?.values() ?: []).each { spec ->
                String s = spec as String
                if (s.startsWith(PREREQ_PREFIX)) return
                String upstream = s.contains('.') ? s.substring(0, s.indexOf('.')) : s
                ((List) adjacency[upstream]) << id
                indegree[id] = (indegree[id] as int) + 1
            }
        }

        def queue = new ArrayDeque<String>()
        indegree.each { id, deg -> if (deg == 0) queue.add(id as String) }

        int processed = 0
        while (!queue.isEmpty()) {
            String id = queue.poll()
            processed++
            ((List) adjacency[id]).each { downstream ->
                int newDeg = (indegree[downstream] as int) - 1
                indegree[downstream] = newDeg
                if (newDeg == 0) queue.add(downstream as String)
            }
        }

        if (processed < nodes.size()) {
            def stuck = indegree.findAll { _, deg -> (deg as int) > 0 }.keySet()
            throw new IllegalArgumentException(
                "[FlowCodegen] Cycle detected in pipeline DAG. Stuck nodes: ${stuck}")
        }
    }

    /** prompts/*.yaml의 action ↔ pipeline node action 1:1 매칭. lenient 시 issues에 누적. */
    private static void collectActionMappingIssues(
        List<Map> nodes, Map<String, File> actionToFile, List issues) {
        def llmTypes = ['llm', 'template'] as Set
        nodes.each { node ->
            String type = (node.type as String)?.toLowerCase()
            if (!llmTypes.contains(type)) return
            String action = (node.action ?: node.id) as String
            if (!actionToFile.containsKey(action)) {
                issues << ("Node '${node.id}' (type=${type}) references action '${action}' " +
                    "but no prompts/*.yaml declares 'action: ${action}'")
            }
        }
    }

    /** prompt {{var}} 참조 ↔ 노드 inputs 키 매칭. */
    private static void collectPromptVariableIssues(
        List<Map> nodes, Map<String, File> actionToFile, List issues) {
        def llmTypes = ['llm', 'template'] as Set
        Yaml yaml = new Yaml()

        nodes.each { node ->
            String type = (node.type as String)?.toLowerCase()
            if (!llmTypes.contains(type)) return
            String action = (node.action ?: node.id) as String
            File promptFile = actionToFile[action]
            if (promptFile == null) return // collectActionMappingIssues에서 이미 보고됨

            Map<String, Object> promptYaml = yaml.load(promptFile.text)
            Set<String> usedVars = collectVariables(promptYaml)
            Set<String> inputKeys = ((node.inputs as Map)?.keySet() ?: []).collect { it as String } as Set

            usedVars.each { var ->
                if (!inputKeys.contains(var)) {
                    issues << ("Node '${node.id}' prompt '${action}' references " +
                        "{{${var}}} but no such key in node.inputs (declared: ${inputKeys})")
                }
            }
        }
    }

    // ── private helpers ──

    private static Map<String, File> scanPrompts(File promptsDir) {
        Map<String, File> map = new LinkedHashMap<>()
        Yaml yaml = new Yaml()
        promptsDir.listFiles({ File f -> f.name.endsWith('.yaml') || f.name.endsWith('.yml') } as java.io.FileFilter)
            ?.each { File f ->
                try {
                    Map<String, Object> data = yaml.load(f.text)
                    String action = data?.get('action') as String
                    if (action != null) map.put(action, f)
                } catch (Exception e) {
                    System.err.println("[FlowCodegen] Failed to read ${f}: ${e.message}")
                }
            }
        return map
    }

    private static Set<String> collectVariables(Map<String, Object> promptYaml) {
        Set<String> vars = new LinkedHashSet<>()
        ['promptTemplate', 'systemTemplate', 'userTemplate'].each { key ->
            Object tmpl = promptYaml.get(key)
            if (tmpl instanceof String) {
                java.util.regex.Matcher m = VAR_PATTERN.matcher(tmpl as String)
                while (m.find()) vars.add(m.group(1))
            }
        }
        return vars
    }
}
