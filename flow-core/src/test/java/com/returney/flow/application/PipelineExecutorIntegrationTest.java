package com.returney.flow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.returney.flow.port.PipelineRunnerFactory;
import com.returney.flow.domain.definition.NodeType;
import com.returney.flow.domain.definition.PipelineDefinition;
import com.returney.flow.domain.definition.PipelineEdge;
import com.returney.flow.domain.definition.PipelineNode;
import com.returney.flow.domain.execution.ExecutionConfig;
import com.returney.flow.domain.execution.NodeResult;
import com.returney.flow.domain.execution.NodeStatus;
import com.returney.flow.domain.execution.PipelineResult;
import com.returney.flow.domain.llm.LlmCallEvent;
import com.returney.flow.domain.llm.LlmCallException;
import com.returney.flow.domain.llm.LlmRawResponse;
import com.returney.flow.domain.llm.LlmRequest;
import com.returney.flow.port.ExecutionListener;
import com.returney.flow.port.LlmExecutor;
import com.returney.flow.port.NodeOutputExtractor;
import com.returney.flow.port.PipelineRunner;
import com.returney.flow.port.PromptRenderer;
import com.returney.flow.port.ServerNodeExecutor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * PipelineExecutor + NodeExecutor + LlmNodeRunner end-to-end 통합 테스트.
 *
 * <p>실제 LLM/HTTP 없이 stub 포트들로 다양한 DAG 시나리오를 검증한다.
 */
class PipelineExecutorIntegrationTest {

  private static final PipelineRunnerFactory FACTORY =
      ServiceLoader.load(PipelineRunnerFactory.class).findFirst().orElseThrow();

  private static PipelineNode llmNode(String id, Map<String, String> inputs) {
    return new PipelineNode(id, id, NodeType.LLM, inputs, "java.lang.String", false);
  }

  private static PipelineNode scatterNode(String id) {
    return new PipelineNode(id, id, NodeType.SCATTER, Map.of(), "java.lang.String", false);
  }

  private static PipelineNode gatherNode(String id, Map<String, String> inputs) {
    return new PipelineNode(id, id, NodeType.GATHER, inputs, "java.lang.String", false);
  }

  private static PipelineDefinition definition(List<PipelineNode> nodes, List<PipelineEdge> edges) {
    return new PipelineDefinition("test", 1, nodes, edges, List.of());
  }

  /** 모든 호출에 동일 응답을 반환하는 stub. */
  private static LlmExecutor fixedResponse(String text) {
    return (req, ctx) -> new LlmRawResponse(text, 1, 1, 2, 0, 0);
  }

  /** node.action을 그대로 반환하는 단순 PromptRenderer. */
  private static final PromptRenderer ECHO_RENDERER = new PromptRenderer() {
    @Override public String render(String action, Map<String, String> variables) { return action; }
    @Override public String getModel(String action) { return "claude-sonnet-4-6"; }
    @Override public int getThinkingBudget(String action) { return 0; }
  };

  private static final NodeOutputExtractor NULL_EXTRACTOR =
      (nodeId, fieldName, output) -> { throw new IllegalStateException("no field refs in test"); };

  private static final ServerNodeExecutor NULL_SERVER =
      new ServerNodeExecutor() {
        @Override public boolean supports(String id) { return false; }
        @Override public List<String> scatter(String id, Map<String, Object> i) { throw new IllegalStateException(); }
        @Override public String gather(String id, List<String> c) { throw new IllegalStateException(); }
        @Override public String transform(String id, Map<String, Object> i) { throw new IllegalStateException(); }
      };

  /** 발생한 listener 이벤트를 순서대로 캡처. */
  private static final class CapturingListener implements ExecutionListener {
    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    @Override public void onNodeStarted(String n, long t) { events.add("started:" + n); }
    @Override public void onNodeCompleted(String n, NodeResult r) { events.add("completed:" + n); }
    @Override public void onNodeFailed(String n, String e) { events.add("failed:" + n); }
    @Override public void onNodeSkipped(String n) { events.add("skipped:" + n); }
    @Override public void onFlowCompleted(PipelineResult r) { events.add("flow_completed"); }
    @Override public void onLlmCall(LlmCallEvent ev) { events.add("llm:" + ev.resolvedModel() + ":" + ev.success()); }
  }

  // ── 시나리오 ────────────────────────────────────────────────────────────

  @Test
  void linear_DAG_executes_nodes_in_topological_order() {
    // a → b → c
    var def = definition(
        List.of(llmNode("a", Map.of()), llmNode("b", Map.of("x", "a")), llmNode("c", Map.of("x", "b"))),
        List.of(new PipelineEdge("a", "b"), new PipelineEdge("b", "c")));

    var listener = new CapturingListener();
    PipelineRunner runner = FACTORY.assemble(
        fixedResponse("ok"), ECHO_RENDERER, NULL_SERVER, NULL_EXTRACTOR, listener,
        Executors.newVirtualThreadPerTaskExecutor());

    PipelineResult result = runner.run(
        def, "session-1", Set.of("a", "b", "c"),
        ExecutionConfig.defaults(), null, Map.of()).join();

    assertThat(result.failedNodes()).isEmpty();
    assertThat(result.nodeResults()).containsOnlyKeys("a", "b", "c");

    // 시작 순서: a → b → c (선형)
    var startedOrder = listener.events.stream().filter(e -> e.startsWith("started:")).toList();
    assertThat(startedOrder).containsExactly("started:a", "started:b", "started:c");
    assertThat(listener.events).contains("flow_completed");
  }

  @Test
  void diamond_DAG_runs_parallel_branches_then_joins() {
    //     a
    //    / \
    //   b   c
    //    \ /
    //     d
    var def = definition(
        List.of(
            llmNode("a", Map.of()),
            llmNode("b", Map.of("x", "a")),
            llmNode("c", Map.of("x", "a")),
            llmNode("d", Map.of("fromB", "b", "fromC", "c"))),
        List.of(
            new PipelineEdge("a", "b"),
            new PipelineEdge("a", "c"),
            new PipelineEdge("b", "d"),
            new PipelineEdge("c", "d")));

    var listener = new CapturingListener();
    PipelineRunner runner = FACTORY.assemble(
        fixedResponse("ok"), ECHO_RENDERER, NULL_SERVER, NULL_EXTRACTOR, listener,
        Executors.newVirtualThreadPerTaskExecutor());

    runner.run(def, "session-2", Set.of("a", "b", "c", "d"),
        ExecutionConfig.defaults(), null, Map.of()).join();

    // a는 1번째, d는 마지막. b/c는 a 다음 ~ d 이전 (어느 순서든 OK)
    var startedOrder = listener.events.stream().filter(e -> e.startsWith("started:")).toList();
    assertThat(startedOrder.get(0)).isEqualTo("started:a");
    assertThat(startedOrder.get(3)).isEqualTo("started:d");
    assertThat(startedOrder.subList(1, 3))
        .containsExactlyInAnyOrder("started:b", "started:c");
  }

  @Test
  void node_failure_skips_only_its_downstream_others_continue() {
    //   a       d
    //   |       |
    //   b       e
    //   |
    //   c
    // a→b→c branch fails at b. d→e branch unaffected.
    Map<String, LlmExecutor> perNode = new ConcurrentHashMap<>();
    LlmExecutor selector = (req, ctx) -> {
      // action == nodeId in this test
      String action = ECHO_RENDERER.render(req.prompt(), Map.of());  // not used here
      // Just route by prompt content. ECHO_RENDERER.render returns action which equals node id.
      return new LlmRawResponse(req.prompt(), 1, 1, 2, 0, 0);
    };
    // Simpler: throw on "b", succeed elsewhere
    LlmExecutor failingOnB = (req, ctx) -> {
      if ("b".equals(req.prompt())) throw new LlmCallException("intentional");
      return new LlmRawResponse("ok", 1, 1, 2, 0, 0);
    };

    var def = definition(
        List.of(
            llmNode("a", Map.of()),
            llmNode("b", Map.of("x", "a")),
            llmNode("c", Map.of("x", "b")),
            llmNode("d", Map.of()),
            llmNode("e", Map.of("x", "d"))),
        List.of(
            new PipelineEdge("a", "b"),
            new PipelineEdge("b", "c"),
            new PipelineEdge("d", "e")));

    var listener = new CapturingListener();
    PipelineRunner runner = FACTORY.assemble(
        failingOnB, ECHO_RENDERER, NULL_SERVER, NULL_EXTRACTOR, listener,
        Executors.newVirtualThreadPerTaskExecutor());

    PipelineResult result = runner.run(
        def, "session-3", Set.of("a", "b", "c", "d", "e"),
        ExecutionConfig.defaults(), null, Map.of()).join();

    assertThat(result.failedNodes()).containsExactly("b");
    // b 실패 → c 스킵
    assertThat(listener.events).contains("failed:b", "skipped:c");
    // d, e는 정상 완료
    assertThat(listener.events).contains("completed:d", "completed:e");
    // a는 정상 완료
    assertThat(listener.events).contains("completed:a");
  }

  @Test
  void scatter_then_fanout_llm_then_gather_executes_each_chunk() {
    // split (scatter, 3 chunks) → analyze (llm, fan-out) → merge (gather)
    var def = definition(
        List.of(
            scatterNode("split"),
            llmNode("analyze", Map.of("upstream", "split")),
            gatherNode("merge", Map.of("upstream", "analyze"))),
        List.of(
            new PipelineEdge("split", "analyze"),
            new PipelineEdge("analyze", "merge")));

    List<String> chunks = List.of("c1", "c2", "c3");

    ServerNodeExecutor server = new ServerNodeExecutor() {
      @Override public boolean supports(String id) { return Set.of("split", "merge").contains(id); }
      @Override public List<String> scatter(String id, Map<String, Object> i) { return chunks; }
      @Override public String gather(String id, List<String> c) { return "merged:" + String.join(",", c); }
      @Override public String transform(String id, Map<String, Object> i) { throw new IllegalStateException(); }
    };

    java.util.concurrent.atomic.AtomicInteger llmCallCount = new java.util.concurrent.atomic.AtomicInteger();
    LlmExecutor llmReflectingChunk = (req, ctx) -> {
      llmCallCount.incrementAndGet();
      return new LlmRawResponse("processed:" + req.prompt(), 1, 1, 2, 0, 0);
    };

    PipelineRunner runner = FACTORY.assemble(
        llmReflectingChunk, ECHO_RENDERER, server, NULL_EXTRACTOR, ExecutionListener.noop(),
        Executors.newVirtualThreadPerTaskExecutor());

    PipelineResult result = runner.run(
        def, "session-4", Set.of("split", "analyze", "merge"),
        ExecutionConfig.defaults(), null, Map.of()).join();

    assertThat(result.failedNodes()).isEmpty();
    // gather 결과는 3개 청크 처리 결과를 합친 것
    String mergeOutput = result.nodeResults().get("merge").output();
    assertThat(mergeOutput).startsWith("merged:");
    assertThat(mergeOutput.split(",")).hasSize(3);
    // analyze는 fan-out으로 chunks.size()번 LLM 호출
    assertThat(llmCallCount.get()).isEqualTo(3);
  }

  @Test
  void seedResults_pre_populate_skipped_nodes_so_downstream_can_run() {
    // a → b. seed로 a를 미리 채워서 a 실행 안 됐는데도 b가 돌게.
    var def = definition(
        List.of(llmNode("a", Map.of()), llmNode("b", Map.of("fromA", "a"))),
        List.of(new PipelineEdge("a", "b")));

    Map<String, NodeResult> seed = Map.of(
        "a", NodeResult.of("seeded-a-output", 0, 0, 0));

    var listener = new CapturingListener();
    PipelineRunner runner = FACTORY.assemble(
        fixedResponse("ok"), ECHO_RENDERER, NULL_SERVER, NULL_EXTRACTOR, listener,
        Executors.newVirtualThreadPerTaskExecutor());

    // nodeIds에 "a" 빼고 "b"만 — b의 input은 seed로 해결
    PipelineResult result = runner.run(
        def, "session-5", Set.of("b"),
        ExecutionConfig.defaults(), seed, Map.of()).join();

    assertThat(result.failedNodes()).isEmpty();
    // b의 결과는 정상 생성 (seed로 input 해결됨)
    assertThat(result.nodeResults()).containsKey("b");
    // a는 실행 안 됨 (started 이벤트 없음)
    assertThat(listener.events).noneMatch(e -> e.startsWith("started:a"));
  }

  @Test
  void prerequisites_passed_through_to_LLM_inputs() {
    // a의 input은 Prerequisites.greeting
    Map<String, String> aInputs = new LinkedHashMap<>();
    aInputs.put("msg", "Prerequisites.greeting");
    var def = definition(
        List.of(new PipelineNode("a", "a", NodeType.LLM, aInputs, "java.lang.String", false)),
        List.of());

    // LLM에 들어간 변수를 캡처
    java.util.concurrent.atomic.AtomicReference<String> seenPrompt = new java.util.concurrent.atomic.AtomicReference<>();
    LlmExecutor capturing = (req, ctx) -> {
      seenPrompt.set(req.prompt());
      return new LlmRawResponse("ok", 1, 1, 2, 0, 0);
    };

    PromptRenderer reflectingRenderer = new PromptRenderer() {
      @Override public String render(String action, Map<String, String> variables) {
        // greeting 변수 값을 그대로 prompt로 사용
        return variables.getOrDefault("msg", "<empty>");
      }
      @Override public String getModel(String action) { return "claude-sonnet-4-6"; }
      @Override public int getThinkingBudget(String action) { return 0; }
    };

    PipelineRunner runner = FACTORY.assemble(
        capturing, reflectingRenderer, NULL_SERVER, NULL_EXTRACTOR, ExecutionListener.noop(),
        Executors.newVirtualThreadPerTaskExecutor());

    runner.run(def, "session-6", Set.of("a"),
        ExecutionConfig.defaults(), null, Map.of("greeting", "hello-world")).join();

    assertThat(seenPrompt.get()).isEqualTo("hello-world");
  }

  @Test
  void onFlowCompleted_fires_exactly_once() {
    var def = definition(
        List.of(llmNode("a", Map.of()), llmNode("b", Map.of("x", "a"))),
        List.of(new PipelineEdge("a", "b")));

    var listener = new CapturingListener();
    PipelineRunner runner = FACTORY.assemble(
        fixedResponse("ok"), ECHO_RENDERER, NULL_SERVER, NULL_EXTRACTOR, listener,
        Executors.newVirtualThreadPerTaskExecutor());

    runner.run(def, UUID.randomUUID().toString(), Set.of("a", "b"),
        ExecutionConfig.defaults(), null, Map.of()).join();

    long flowCompleted = listener.events.stream().filter("flow_completed"::equals).count();
    assertThat(flowCompleted).isEqualTo(1);
  }

  @Test
  void critical_node_failure_records_status_FAILED() {
    LlmExecutor alwaysFailing = (req, ctx) -> { throw new LlmCallException("nope"); };

    var def = definition(
        List.of(llmNode("a", Map.of())),
        List.of());

    var listener = new CapturingListener();
    PipelineRunner runner = FACTORY.assemble(
        alwaysFailing, ECHO_RENDERER, NULL_SERVER, NULL_EXTRACTOR, listener,
        Executors.newVirtualThreadPerTaskExecutor());

    PipelineResult result = runner.run(
        def, "s", Set.of("a"), ExecutionConfig.defaults(), null, Map.of()).join();

    assertThat(result.failedNodes()).containsExactly("a");
    assertThat(listener.events).contains("failed:a");
  }
}
