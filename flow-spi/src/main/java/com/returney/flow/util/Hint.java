package com.returney.flow.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 도메인 record component 또는 type에 LLM에게 보여줄 hint를 단다.
 *
 * <p>{@code SchemaRenderer}가 record를 reflective walk하며 이 어노테이션을 읽어 사람이 읽을 수 있는
 * 평문 schema 텍스트를 만들고, {@code LlmNodeRunner}가 user prompt 끝에 자동으로 첨부한다.
 *
 * <p>yaml prompt에 매번 손으로 출력 스펙을 박는 것을 대체 — Java record가 진실 소스가 된다.
 *
 * <p>예:
 * <pre>{@code
 * public record TopicData(
 *     @Hint("t1, t2... 형식 식별자") String topicId,
 *     @Hint("주제 제목") String title,
 *     @Hint(value = "주제 유형", enumValues = {"gap","choice","achievement"}) String type,
 *     @Hint(value = "근거 retroPoint id 목록") List<String> evidence
 * ) {}
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.TYPE})
public @interface Hint {

  /** 사람-읽기용 description. 비어있으면 schema 텍스트에 description 줄을 안 넣는다. */
  String value() default "";

  /** String 필드에 enum-like 제약. 비어있으면 미적용. */
  String[] enumValues() default {};

  /** false면 nullable로 표기 (LLM에게 "이 필드는 선택"임을 알림). 기본 true. */
  boolean required() default true;
}
