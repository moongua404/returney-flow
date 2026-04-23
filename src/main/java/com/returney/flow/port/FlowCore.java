package com.returney.flow.port;

import com.returney.flow.application.LlmNodeRunner;
import com.returney.flow.application.NodeExecutor;
import com.returney.flow.application.NodeInputResolver;
import com.returney.flow.application.PipelineExecutor;
import java.util.concurrent.Executor;

/**
 * flow-core 진입점 팩토리.
 *
 * <p>SPI 구현체를 받아 {@link PipelineRunner}를 조립한다.
 * 소비자는 내부 구현 클래스를 직접 참조하지 않아도 된다.
 */
public final class FlowCore {

  private FlowCore() {}

  public static PipelineRunner create(
      LlmExecutor llmExecutor,
      PromptRenderer promptRenderer,
      ServerNodeExecutor serverNodeExecutor,
      NodeOutputExtractor extractor,
      ExecutionListener listener,
      Executor executor) {

    var resolver = new NodeInputResolver(extractor);
    var runner   = new LlmNodeRunner(llmExecutor, promptRenderer, resolver, executor);
    var node     = new NodeExecutor(runner, serverNodeExecutor, resolver, listener);
    return new PipelineExecutor(node, listener, executor);
  }
}
