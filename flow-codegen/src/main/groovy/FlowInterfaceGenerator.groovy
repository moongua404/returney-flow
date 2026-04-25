import org.yaml.snakeyaml.Yaml

class FlowInterfaceGenerator {

    static void generate(File pipelineYaml, File outputDir, String pkg) {
        def pipeline = new Yaml().load(pipelineYaml.text) as Map

        // pipelineYaml과 같은 디렉터리의 prompts/ 를 자동 탐색.
        // 없으면 prompts 관련 검증은 스킵.
        File promptsDir = new File(pipelineYaml.parentFile, 'prompts')
        FlowValidator.validateAll(pipeline, promptsDir.exists() ? promptsDir : null)

        def model  = FlowModel.from(pipeline, pkg)
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
}
