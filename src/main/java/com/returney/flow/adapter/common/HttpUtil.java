package com.returney.flow.adapter.common;

import com.returney.flow.domain.llm.LlmCallException;
import com.returney.flow.domain.llm.LlmNetworkException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Java HttpClient 기반 공통 HTTP 유틸. */
public final class HttpUtil {

  private static final int MAX_RETRY = 2;
  private static final long BASE_BACKOFF_MS = 5_000;
  private static final long MAX_BACKOFF_MS = 15_000;

  private HttpUtil() {}

  /** 커넥션 타임아웃 10초로 설정된 HttpClient를 생성한다. */
  public static HttpClient newClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
  }

  /**
   * JSON POST 요청을 실행하고 응답을 반환한다.
   * 429 응답에 대해 최대 {@value MAX_RETRY}회 지수 백오프 재시도한다.
   */
  public static HttpResponse<String> postJson(
      HttpClient client, String url, String body, Map<String, String> headers)
      throws IOException, InterruptedException {
    HttpResponse<String> response = doPost(client, url, body, headers);
    for (int attempt = 0; attempt < MAX_RETRY
        && (response.statusCode() == 429 || response.statusCode() == 500 || response.statusCode() == 503);
        attempt++) {
      long sleepMs = Math.min(BASE_BACKOFF_MS * (1L << attempt), MAX_BACKOFF_MS);
      Thread.sleep(sleepMs);
      response = doPost(client, url, body, headers);
    }
    return response;
  }

  private static HttpResponse<String> doPost(
      HttpClient client, String url, String body, Map<String, String> headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofMinutes(5))
        .POST(HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(builder::header);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /**
   * LLM 응답에서 마크다운 코드 블록을 제거한다.
   * LLM이 응답을 ```json ... ``` 형태로 감싸는 경우를 처리한다.
   */
  public static String stripCodeBlock(String text) {
    if (text == null) return null;
    String trimmed = text.strip();
    if (trimmed.startsWith("```")) {
      int firstNewline = trimmed.indexOf('\n');
      if (firstNewline != -1) {
        trimmed = trimmed.substring(firstNewline + 1);
      }
      if (trimmed.endsWith("```")) {
        trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).strip();
      }
    }
    return trimmed;
  }

  /**
   * JSON POST 요청을 실행하고 성공 응답 본문을 반환한다.
   * non-2xx이면 {@link LlmCallException}, 네트워크 오류면 {@link LlmNetworkException}을 던진다.
   */
  public static String postJsonOrThrow(
      HttpClient client, String url, String body, Map<String, String> headers, String provider) {
    try {
      HttpResponse<String> response = postJson(client, url, body, headers);
      if (!isSuccess(response.statusCode())) {
        throw LlmCallException.fromHttpError(provider, response.statusCode(), response.body());
      }
      return response.body();
    } catch (InterruptedException e) {
      throw LlmNetworkException.interrupted(provider, e);
    } catch (LlmCallException e) {
      throw e;
    } catch (Exception e) {
      throw LlmNetworkException.wrap(provider, e);
    }
  }

  /** HTTP 상태 코드가 성공(2xx)인지 확인한다. */
  public static boolean isSuccess(int statusCode) {
    return statusCode >= 200 && statusCode < 300;
  }
}
