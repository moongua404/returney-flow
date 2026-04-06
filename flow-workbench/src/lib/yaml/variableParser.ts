/**
 * YAML 템플릿에서 {{변수명}} 패턴을 추출한다.
 * config-ref 패턴 ({{configKey:sectionKey}})의 configKey도 포함.
 */
export function extractTemplateVariables(yaml: string): string[] {
  const regex = /\{\{(\w+)(?::\w+)?\}\}/g;
  const vars = new Set<string>();
  let match: RegExpExecArray | null;
  while ((match = regex.exec(yaml)) !== null) {
    vars.add(match[1]);
  }
  return Array.from(vars);
}
