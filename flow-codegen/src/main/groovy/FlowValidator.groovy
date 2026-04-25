class FlowValidator {

    private static final String PREREQ_PREFIX = 'Prerequisites.'

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
}
