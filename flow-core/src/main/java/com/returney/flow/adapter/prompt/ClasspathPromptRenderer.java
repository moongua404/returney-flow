package com.returney.flow.adapter.prompt;

import com.returney.flow.port.PromptRenderer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * 클래스패스 {@code prompts/{action}.yaml} 기반 PromptRenderer.
 *
 * <p>Spring 의존 없이 SnakeYAML만 사용. 생성된 PipelineBase가 내부적으로 인스턴스화한다.
 */
public final class ClasspathPromptRenderer implements PromptRenderer {

  private static final Pattern CONFIG_REF = Pattern.compile("\\{\\{(\\w+):(\\w+)\\}\\}");

  private final Map<String, String> systemTemplates = new HashMap<>();
  private final Map<String, String> userTemplates = new HashMap<>();
  private final Map<String, String> models = new HashMap<>();
  private final Map<String, Integer> thinkingBudgets = new HashMap<>();
  @SuppressWarnings("unchecked")
  private final Map<String, Map<String, Object>> fullYaml = new HashMap<>();

  private ClasspathPromptRenderer() {}

  public static ClasspathPromptRenderer forActions(String... actions) {
    ClasspathPromptRenderer r = new ClasspathPromptRenderer();
    for (String action : actions) r.load(action);
    return r;
  }

  @SuppressWarnings("unchecked")
  private void load(String action) {
    InputStream is = getClass().getClassLoader()
        .getResourceAsStream("prompts/" + action + ".yaml");
    if (is == null) return;
    try {
      Map<String, Object> data = new Yaml().load(
          new String(is.readAllBytes(), StandardCharsets.UTF_8));
      String act = data.containsKey("action") ? (String) data.get("action") : action;
      String sys = (String) data.get("systemTemplate");
      String usr = (String) data.get("userTemplate");
      String model = (String) data.get("model");
      if (sys != null) systemTemplates.put(act, sys);
      if (usr != null) userTemplates.put(act, usr);
      if (model != null) models.put(act, model);
      fullYaml.put(act, data);
      Object thinking = data.get("thinking");
      int budget = -1;
      if (thinking instanceof Number) budget = ((Number) thinking).intValue();
      else if (thinking instanceof Boolean) budget = (Boolean) thinking ? -1 : 0;
      thinkingBudgets.put(act, budget);
    } catch (Exception ignored) {}
  }

  @Override
  public String render(String action, Map<String, String> variables) {
    String template = userTemplates.get(action);
    if (template == null) return "";
    return resolveRefs(interpolate(template, variables), action, variables);
  }

  @Override
  public String renderSystemPrompt(String action, Map<String, String> variables) {
    String template = systemTemplates.get(action);
    if (template == null) return null;
    return resolveRefs(interpolate(template, variables), action, variables);
  }

  @Override
  public String renderUserPrompt(String action, Map<String, String> variables) {
    String template = userTemplates.get(action);
    if (template == null) return "";
    return resolveRefs(interpolate(template, variables), action, variables);
  }

  @Override
  public String getModel(String action) {
    return models.get(action);
  }

  @Override
  public int getThinkingBudget(String action) {
    return thinkingBudgets.getOrDefault(action, -1);
  }

  private static String interpolate(String template, Map<String, String> vars) {
    String result = template;
    for (Map.Entry<String, String> e : vars.entrySet()) {
      String val = e.getValue() != null ? e.getValue() : "";
      result = result.replace("{{" + e.getKey() + "}}", val);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private String resolveRefs(String text, String action, Map<String, String> vars) {
    Map<String, Object> data = fullYaml.get(action);
    if (data == null) return text;
    Matcher m = CONFIG_REF.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String configKey = m.group(1);
      String sectionKey = m.group(2);
      String branchId = vars.get(configKey);
      if (branchId == null) branchId = vars.get(configKey + "Id");
      String value = lookupBranch(data, configKey, branchId, sectionKey);
      m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : ""));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static String lookupBranch(
      Map<String, Object> data, String key, String branchId, String section) {
    if (branchId == null) return null;
    Object obj = data.get(key);
    if (!(obj instanceof Map)) return null;
    Map<String, Object> config = (Map<String, Object>) obj;
    Object branch = config.get(branchId);
    if (!(branch instanceof Map)) return null;
    Object val = ((Map<String, Object>) branch).get(section);
    return val != null ? val.toString() : null;
  }
}
