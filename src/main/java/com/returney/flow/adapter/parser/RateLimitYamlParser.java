package com.returney.flow.adapter.parser;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

/** rate-limits.yaml 파싱. */
public class RateLimitYamlParser {

  static class Config {
    public List<Entry> models;
  }

  static class Entry {
    public String prefix;
    public int rpm;
    public int tpm;
  }

  /** InputStream에서 prefix → [rpm, tpm] 맵을 파싱한다. */
  public Map<String, int[]> parse(InputStream inputStream) {
    Config config = new Yaml(new Constructor(Config.class, new LoaderOptions())).load(inputStream);
    Map<String, int[]> limits = new LinkedHashMap<>();
    for (Entry e : config.models) {
      limits.put(e.prefix, new int[]{e.rpm, e.tpm});
    }
    return limits;
  }
}
