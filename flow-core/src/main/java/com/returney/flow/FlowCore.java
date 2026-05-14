package com.returney.flow;

import com.returney.flow.application.LlmNodeRunner;
import com.returney.flow.application.NodeExecutor;
import com.returney.flow.application.NodeInputResolver;
import com.returney.flow.application.PipelineExecutor;
import com.returney.flow.port.ExecutionListener;
import com.returney.flow.port.LlmExecutor;
import com.returney.flow.port.NodeOutputExtractor;
import com.returney.flow.port.PipelineRunner;
import com.returney.flow.port.PromptRenderer;
import com.returney.flow.port.ServerNodeExecutor;
import java.util.concurrent.Executor;

/**
 * flow-core 내부 조립 팩토리. 외부 소비자는 {@link FlowCoreRunnerFactory}를 통해 접근한다.
 */
final class FlowCore {

  private FlowCore() {}

  public static PipelineRunner create(
      LlmExecutor llmExecutor,
      PromptRenderer promptRenderer,
      ServerNodeExecutor serverNodeExecutor,
      NodeOutputExtractor extractor,
      ExecutionListener listener,
      Executor executor) {

    var resolver = new NodeInputResolver(extractor);
    var runner   = new LlmNodeRunner(llmExecutor, promptRenderer, resolver, executor, listener);
    var node     = new NodeExecutor(runner, serverNodeExecutor, resolver, listener);
    return new PipelineExecutor(node, listener, executor);
  }
}
