package com.returney.flow;

import com.returney.flow.adapter.parser.PipelineYamlParser;
import com.returney.flow.adapter.prompt.ClasspathPromptRenderer;
import com.returney.flow.domain.definition.PipelineDefinition;
import com.returney.flow.port.ExecutionListener;
import com.returney.flow.port.LlmExecutor;
import com.returney.flow.port.NodeOutputExtractor;
import com.returney.flow.port.PipelineRunner;
import com.returney.flow.port.PipelineRunnerFactory;
import com.returney.flow.port.PromptRenderer;
import com.returney.flow.port.ServerNodeExecutor;
import java.util.concurrent.Executor;

/** ServiceLoader 등록 구현체. 외부 소비자가 직접 참조하지 않는다. */
public final class FlowCoreRunnerFactory implements PipelineRunnerFactory {

  @Override
  public PipelineDefinition loadDefinition(String classpathPath) {
    return new PipelineYamlParser().parseFromClasspath(classpathPath);
  }

  @Override
  public PromptRenderer classpathRenderer(String... actions) {
    return ClasspathPromptRenderer.forActions(actions);
  }

  @Override
  public PipelineRunner assemble(
      LlmExecutor llmExecutor,
      PromptRenderer promptRenderer,
      ServerNodeExecutor serverNodeExecutor,
      NodeOutputExtractor extractor,
      ExecutionListener listener,
      Executor executor) {
    return FlowCore.create(llmExecutor, promptRenderer, serverNodeExecutor, extractor, listener, executor);
  }
}
