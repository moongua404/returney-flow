import org.yaml.snakeyaml.Yaml

class FlowInterfaceGenerator {

    private static final String PIPELINE_SUBPACKAGE = 'pipeline'

    /** 단일 yaml 산출물 생성. *Base.java 하나만 emit; pipeline subpackage. */
    static void generate(File pipelineYaml, File outputDir, String pkg) {
        generate(pipelineYaml, outputDir, pkg, false)
    }

    static void generate(File pipelineYaml, File outputDir, String pkg, boolean strictPrompts) {
        def pipeline = new Yaml().load(pipelineYaml.text) as Map

        File promptsDir = new File(pipelineYaml.parentFile, 'prompts')
        FlowValidator.validateAll(pipeline, promptsDir.exists() ? promptsDir : null, strictPrompts)

        String pipelinePkg = pkg + '.' + PIPELINE_SUBPACKAGE
        def model  = FlowModel.from(pipeline, pipelinePkg, pipelineYaml.name)
        def outDir = new File(outputDir, pipelinePkg.replace('.', '/'))
        outDir.mkdirs()

        def serverNodesSource = FlowRenderer.serverNodesInterface(model)
        if (serverNodesSource != null) {
            new File(outDir, "${model.flowName}ServerNodes.java").text = serverNodesSource
            new File(outDir, "${model.flowName}Runner.java").text      = FlowRenderer.pipelineRunner(model)
            println "[FlowCodegen] Generated ${model.flowName}ServerNodes + Runner + Input → ${outDir}"
        } else {
            new File(outDir, "${model.flowName}Runner.java").text = FlowRenderer.pipelineRunner(model)
            println "[FlowCodegen] Generated ${model.flowName}Runner + Input → ${outDir}"
        }
        new File(outDir, "${model.flowName}Input.java").text = FlowRenderer.inputRecord(model)
    }

    /**
     * yamlsDir 안의 {@code *-flow.yaml} 파일을 모두 스캔해 각각 Base 클래스를 생성한다.
     */
    static void generateAll(File yamlsDir, File outputDir, String pkg) {
        generateAll(yamlsDir, outputDir, pkg, false)
    }

    static void generateAll(File yamlsDir, File outputDir, String pkg, boolean strictPrompts) {
        if (!yamlsDir.exists() || !yamlsDir.isDirectory()) {
            throw new IllegalArgumentException("yamlsDir does not exist: ${yamlsDir}")
        }
        File[] yamls = yamlsDir.listFiles({ File f -> f.name.endsWith('-flow.yaml') } as java.io.FileFilter)
        if (yamls == null || yamls.length == 0) {
            throw new IllegalStateException(
                "No *-flow.yaml files found in ${yamlsDir} (need at least one pipeline)")
        }
        for (File yaml : yamls) {
            generate(yaml, outputDir, pkg, strictPrompts)
        }
    }
}
