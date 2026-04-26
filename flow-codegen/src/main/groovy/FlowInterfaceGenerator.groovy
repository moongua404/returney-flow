import org.yaml.snakeyaml.Yaml

class FlowInterfaceGenerator {

    /** 단일 yaml 산출물 생성. 클래스패스의 yamlResourcePath는 yaml 파일명으로 자동 결정. */
    static void generate(File pipelineYaml, File outputDir, String pkg) {
        generate(pipelineYaml, outputDir, pkg, false)
    }

    static void generate(File pipelineYaml, File outputDir, String pkg, boolean strictPrompts) {
        def pipeline = new Yaml().load(pipelineYaml.text) as Map

        File promptsDir = new File(pipelineYaml.parentFile, 'prompts')
        FlowValidator.validateAll(pipeline, promptsDir.exists() ? promptsDir : null, strictPrompts)

        def model  = FlowModel.from(pipeline, pkg, pipelineYaml.name)
        def outDir = new File(outputDir, pkg.replace('.', '/'))
        outDir.mkdirs()

        [
            "${model.flowName}Prerequisites.java":  FlowRenderer.prerequisites(model),
            "${model.flowName}Result.java":         FlowRenderer.resultRecord(model),
            "${model.flowName}FieldExtractor.java": FlowRenderer.fieldExtractor(model),
            "${model.flowName}LlmMiddleware.java":  FlowRenderer.llmMiddleware(model),
            "${model.flowName}Base.java":           FlowRenderer.pipelineBase(model),
        ].each { name, content -> new File(outDir, name).text = content }

        println "[FlowCodegen] Generated ${model.flowName}Pipeline artifacts → ${outDir}"
    }

    /**
     * yamlsDir 안의 {@code *-flow.yaml} 파일을 모두 스캔해 각각 산출물을 생성한다.
     *
     * <p>각 파이프라인의 yaml은 클래스패스 루트에 같은 이름으로 존재해야 한다
     * (생성된 *Base 코드가 {@code parseFromClasspath(yamlName)}로 로드).
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
