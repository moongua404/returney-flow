"use client";

import { useMemo } from "react";
import { extractTemplateVariables } from "@/lib/yaml/variableParser";

export type VariableStatus = "fixture" | "edited" | "missing" | "unused";

export interface VariableEntry {
  name: string;
  value: string;
  fixtureValue: string | null;
  status: VariableStatus;
}

/**
 * YAML 템플릿의 변수와 fixture 데이터를 대조하여
 * Variable Panel에 필요한 변수 목록 + 상태를 산출한다.
 */
export function useVariableParser(
  yaml: string,
  fixtureInputs: Record<string, string>,
  editedValues: Record<string, string>,
): VariableEntry[] {
  return useMemo(() => {
    const templateVars = new Set(extractTemplateVariables(yaml));
    const fixtureKeys = new Set(Object.keys(fixtureInputs));
    const allVars = new Set([...templateVars, ...fixtureKeys]);

    const entries: VariableEntry[] = [];

    for (const name of allVars) {
      const inTemplate = templateVars.has(name);
      const inFixture = fixtureKeys.has(name);
      const fixtureValue = inFixture ? fixtureInputs[name] : null;
      const currentValue = editedValues[name] ?? fixtureValue ?? "";

      let status: VariableStatus;
      if (!inTemplate && inFixture) {
        status = "unused";
      } else if (inTemplate && !inFixture && editedValues[name] === undefined) {
        status = "missing";
      } else if (
        inFixture &&
        editedValues[name] !== undefined &&
        editedValues[name] !== fixtureValue
      ) {
        status = "edited";
      } else {
        status = "fixture";
      }

      entries.push({ name, value: currentValue, fixtureValue, status });
    }

    // 정렬: missing → edited → fixture → unused
    const order: Record<VariableStatus, number> = {
      missing: 0,
      edited: 1,
      fixture: 2,
      unused: 3,
    };
    entries.sort((a, b) => order[a.status] - order[b.status]);

    return entries;
  }, [yaml, fixtureInputs, editedValues]);
}
