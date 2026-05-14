package com.returney.flow.domain.execution;

/**
 * 서버 노드의 두 채널 출력.
 *
 * <ul>
 *   <li>{@code prompt} — 다음 노드의 LLM 프롬프트 컨텍스트로 직결되는 자연어/요약 텍스트.</li>
 *   <li>{@code typed} — 같은 노드의 결과를 도메인 객체로 가져갈 후속 단계용 side output.
 *       {@code null} 이면 단순 텍스트 노드.</li>
 * </ul>
 *
 * <p>한 출력이 *프롬프트 컨텍스트* 와 *비즈니스 데이터* 두 역할을 동시에 가져야 할 때
 * 두 채널을 분리한다. 프롬프트 오염 / 토큰 낭비 없이 도메인 객체를 후속 단계에 흘려보낼 수 있다.
 */
public record NodeOutput(String prompt, Object typed) {

  /** 텍스트만 있는 노드 — 기존 String 리턴과 호환. */
  public static NodeOutput textOnly(String prompt) {
    return new NodeOutput(prompt, null);
  }

  /** 프롬프트 + 도메인 객체 양쪽 모두 채운다. */
  public static NodeOutput of(String prompt, Object typed) {
    return new NodeOutput(prompt, typed);
  }
}
