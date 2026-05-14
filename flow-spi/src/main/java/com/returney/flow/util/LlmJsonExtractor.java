package com.returney.flow.util;

/**
 * LLM 원시 응답에서 JSON 본문을 추출하는 유틸.
 *
 * <p>코드젠이 emit한 {@code parseResult}는 노드 출력에 직접 Gson을 적용하는데, Gemini 등 모델은
 * 종종 ```json ... ``` 마크다운 펜스로 감싼 응답을 돌려준다. 이 클래스는 펜스 제거와 첫 번째
 * {@code {} / {@code [} 위치 탐색을 묶어 codegen 결과물의 robustness를 보장한다.
 *
 * <p>지원 케이스:
 * <ul>
 *   <li>{@code ```json\n{...}\n```} → {@code {...}}
 *   <li>{@code ```\n{...}\n```} → {@code {...}}
 *   <li>{@code 잡담\n{...}} → {@code {...}}
 *   <li>이미 정상 JSON → 그대로
 * </ul>
 *
 * <p>응답이 JSON 객체/배열이 아닌 단순 문자열({@code "text"})인 경우는 호출자가 책임진다 —
 * 이 유틸은 그런 입력을 그대로 통과시키고 후속 파서에서 명확한 에러를 발생시키는 게 낫다.
 */
public final class LlmJsonExtractor {

  private LlmJsonExtractor() {}

  /**
   * 원시 응답에서 JSON 부분을 추출한다.
   *
   * @param response LLM 원시 응답 문자열 (null/blank 허용)
   * @return 추출된 JSON 문자열. 원본이 null이면 {@code "{}"} 반환 (Gson NPE 회피).
   */
  public static String extract(String response) {
    if (response == null || response.isBlank()) {
      return "{}";
    }

    int jsonBlockStart = response.indexOf("```json");
    if (jsonBlockStart != -1) {
      int contentStart = jsonBlockStart + 7;
      int end = response.indexOf("```", contentStart);
      if (end > contentStart) {
        return response.substring(contentStart, end).trim();
      }
    }

    int blockStart = response.indexOf("```");
    if (blockStart != -1) {
      int contentStart = blockStart + 3;
      int end = response.indexOf("```", contentStart);
      if (end > contentStart) {
        return response.substring(contentStart, end).trim();
      }
    }

    String trimmed = response.trim();
    int arrayStart = trimmed.indexOf('[');
    int objectStart = trimmed.indexOf('{');

    int jsonStart = -1;
    if (arrayStart != -1 && (objectStart == -1 || arrayStart < objectStart)) {
      jsonStart = arrayStart;
    } else if (objectStart != -1) {
      jsonStart = objectStart;
    }

    return jsonStart != -1 ? trimmed.substring(jsonStart) : trimmed;
  }
}
