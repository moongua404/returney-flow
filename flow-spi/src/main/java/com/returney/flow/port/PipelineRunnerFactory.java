package com.returney.flow.port;

import com.returney.flow.domain.definition.PipelineDefinition;
import java.util.concurrent.Executor;

/**
 * flow-core 구현체 조립 팩토리 (SPI).
 *
 * <p>ServiceLoader로 구현체를 획득한다. 소비자는 flow-core를 직접 참조하지 않는다.
 */
public interface PipelineRunnerFactory {

  /** 클래스패스 YAML에서 PipelineDefinition을 로드한다. */
  PipelineDefinition loadDefinition(String classpathPath);

  /** 클래스패스 프롬프트 파일 기반 PromptRenderer를 생성한다. */
  PromptRenderer classpathRenderer(String... actions);

  /** SPI 구현체들을 받아 PipelineRunner를 조립한다. */
  PipelineRunner assemble(
      LlmExecutor llmExecutor,
      PromptRenderer promptRenderer,
      ServerNodeExecutor serverNodeExecutor,
      NodeOutputExtractor extractor,
      ExecutionListener listener,
      Executor executor);
}
