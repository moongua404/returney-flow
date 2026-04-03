package com.returney.flow.schema;

import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/** 프롬프트 YAML을 파싱하여 PromptDefinition을 생성한다. */
public class PromptYamlParser {

  /**
   * YAML 문자열을 파싱한다.
   *
   * @throws FlowParseException 파싱 실패 시
   */
  public PromptDefinition parse(String yamlContent) {
    Yaml yaml = new Yaml();
    Map<String, Object> root = yaml.load(yamlContent);
    return parseRoot(root);
  }

  @SuppressWarnings("unchecked")
  private PromptDefinition parseRoot(Map<String, Object> root) {
    if (root == null) {
      throw new FlowParseException("Prompt YAML content is empty");
    }

    String action = getString(root, "action");
    String model = getString(root, "model");
    boolean thinking = Boolean.TRUE.equals(root.get("thinking"));

    // inputs 파싱
    Map<String, InputParameter> inputs = new LinkedHashMap<>();
    Object rawInputs = root.get("inputs");
    if (rawInputs instanceof Map<?, ?> inputMap) {
      for (Map.Entry<?, ?> entry : inputMap.entrySet()) {
        String paramName = entry.getKey().toString();
        if (entry.getValue() instanceof Map<?, ?> paramMap) {
          inputs.put(paramName, parseInputParameter(paramName, (Map<String, Object>) paramMap));
        }
      }
    }

    // output 파싱
    OutputDefinition output = null;
    Object rawOutput = root.get("output");
    if (rawOutput instanceof Map<?, ?> outputMap) {
      String type = outputMap.containsKey("type") ? outputMap.get("type").toString() : "text";
      String description =
          outputMap.containsKey("description") ? outputMap.get("description").toString() : null;
      output = new OutputDefinition(type, description);
    }

    // promptTemplate
    String promptTemplate = null;
    if (root.containsKey("promptTemplate")) {
      promptTemplate = root.get("promptTemplate").toString();
    } else if (root.containsKey("prompt")) {
      promptTemplate = root.get("prompt").toString();
    }

    return new PromptDefinition(action, model, thinking, inputs, output, promptTemplate);
  }

  private InputParameter parseInputParameter(String name, Map<String, Object> map) {
    String type = map.containsKey("type") ? map.get("type").toString() : "string";
    String description =
        map.containsKey("description") ? map.get("description").toString() : null;
    String source = map.containsKey("source") ? map.get("source").toString() : null;
    String format = map.containsKey("format") ? map.get("format").toString() : null;
    boolean required =
        map.containsKey("required") ? Boolean.parseBoolean(map.get("required").toString()) : true;

    return new InputParameter(name, type, description, source, format, required);
  }

  private String getString(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value != null ? value.toString() : null;
  }
}
