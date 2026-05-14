package com.returney.flow.middleware;

import com.returney.flow.domain.llm.LlmCallContext;
import com.returney.flow.domain.llm.LlmCallException;
import com.returney.flow.domain.llm.LlmRawResponse;
import com.returney.flow.domain.llm.LlmRequest;
import com.returney.flow.port.LlmExecutor;

/**
 * LlmExecutor 데코레이터. 기본은 pass-through.
 *
 * <p>코드젠된 모든 PipelineBase가 이 클래스를 공유한다. 호출 전 요청 가공이 필요하면
 * {@link #beforeExecute}를 override한 서브클래스를 PipelineBase의 {@code createLlmMiddleware}에서
 * 반환하면 된다 — 일반적으로는 그대로 둔다.
 */
public class LlmMiddleware implements LlmExecutor {

  protected final LlmExecutor delegate;

  public LlmMiddleware(LlmExecutor delegate) {
    this.delegate = delegate;
  }

  @Override
  public LlmRawResponse execute(LlmRequest request, LlmCallContext context) throws LlmCallException {
    return delegate.execute(beforeExecute(request), context);
  }

  protected LlmRequest beforeExecute(LlmRequest request) {
    return request;
  }
}
