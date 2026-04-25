package com.returney.flow.application;

import com.returney.flow.domain.definition.PipelineDefinition;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 프롬프트 수정 시 다운스트림 캐시 무효화 대상을 계산한다.
 *
 * <p>BFS로 수정된 노드의 모든 다운스트림을 탐색한다.
 */
public class CacheInvalidator {

  /**
   * 수정된 노드와 그 다운스트림 전체를 반환한다.
   *
   * @param flowDef 플로우 정의
   * @param modifiedNodeId 수정된 노드 ID
   * @return 무효화 대상 노드 ID 집합 (modifiedNodeId 포함)
   */
  public Set<String> findInvalidated(PipelineDefinition flowDef, String modifiedNodeId) {
    Set<String> result = new LinkedHashSet<>();
    result.add(modifiedNodeId);

    Deque<String> queue = new ArrayDeque<>();
    queue.add(modifiedNodeId);

    while (!queue.isEmpty()) {
      String current = queue.poll();
      for (String downstream : flowDef.downstreamOf(current)) {
        if (result.add(downstream)) {
          queue.add(downstream);
        }
      }
    }

    return result;
  }
}
