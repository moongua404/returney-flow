package com.returney.flow.port;

import com.returney.flow.domain.definition.PipelineDefinition;
import com.returney.flow.domain.execution.ExecutionConfig;
import com.returney.flow.domain.execution.NodeResult;
import com.returney.flow.domain.execution.PipelineResult;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 파이프라인 실행 진입점 (in-port).
 *
 * <p>소비자는 {@link PipelineRunnerFactory}를 ServiceLoader로 획득해 조립한다.
 */
public interface PipelineRunner {

  CompletableFuture<PipelineResult> run(
      PipelineDefinition def,
      String sessionId,
      Set<String> nodeIds,
      ExecutionConfig config,
      Map<String, NodeResult> seedResults,
      Map<String, Object> prerequisites);
}
