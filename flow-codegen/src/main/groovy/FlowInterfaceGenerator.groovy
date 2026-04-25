import org.yaml.snakeyaml.Yaml

class FlowInterfaceGenerator {

    static void generate(File pipelineYaml, File outputDir, String pkg) {
        def pipeline = new Yaml().load(pipelineYaml.text) as Map

        FlowValidator.validatePrerequisiteRefs(
            pipeline.nodes as List<Map>,
            (pipeline.prerequisites as List<String> ?: []) as Set)

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
