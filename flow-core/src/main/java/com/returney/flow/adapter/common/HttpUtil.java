package com.returney.flow.adapter.common;

import com.returney.flow.domain.llm.LlmCallException;
import com.returney.flow.domain.llm.LlmNetworkException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/** Java HttpClient 기반 공통 HTTP 유틸. */
public final class HttpUtil {


  private HttpUtil() {}

  /** 디폴트 connect timeout(10s)으로 설정된 HttpClient를 생성한다. */
  public static HttpClient newClient() {
    return newClient(10);
  }

  /** 명시적 connect timeout으로 HttpClient 생성. */
  public static HttpClient newClient(int connectTimeoutSec) {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(connectTimeoutSec))
        .build();
  }

  /**
   * JSON POST 요청을 실행하고 응답을 반환한다. 기본 request timeout 5분.
   *
   * <p>재시도는 호출자({@code InternalLlmRouter})가 retry policy + Retry-After 헤더를
   * 보고 처리한다. 본 메서드는 1회 호출만 수행 — 라우터의 retry와 이중 발생 방지.
   */
  public static HttpResponse<String> postJson(
      HttpClient client, String url, String body, Map<String, String> headers)
      throws IOException, InterruptedException {
    return postJson(client, url, body, headers, 300);
  }

  /** 명시적 request timeout(초)으로 postJson 호출. */
  public static HttpResponse<String> postJson(
      HttpClient client, String url, String body, Map<String, String> headers, int requestTimeoutSec)
      throws IOException, InterruptedException {
    return doPost(client, url, body, headers, requestTimeoutSec);
  }

  private static HttpResponse<String> doPost(
      HttpClient client, String url, String body, Map<String, String> headers, int requestTimeoutSec)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(requestTimeoutSec))
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
    return postJsonOrThrow(client, url, body, headers, provider, 300);
  }

  /** 명시적 request timeout(초)으로 postJsonOrThrow 호출. */
  public static String postJsonOrThrow(
      HttpClient client, String url, String body, Map<String, String> headers,
      String provider, int requestTimeoutSec) {
    try {
      HttpResponse<String> response = postJson(client, url, body, headers, requestTimeoutSec);
      if (!isSuccess(response.statusCode())) {
        Long retryAfterMs = parseRetryAfter(response.headers()).orElse(null);
        throw LlmCallException.fromHttpError(
            provider, response.statusCode(), response.body(), retryAfterMs);
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

  /**
   * RFC 7231 Retry-After 헤더를 파싱한다. 두 형식 지원:
   * <ul>
   *   <li>delta-seconds: {@code Retry-After: 60}
   *   <li>HTTP-date (RFC 1123): {@code Retry-After: Wed, 21 Oct 2026 07:28:00 GMT}
   * </ul>
   *
   * @return 대기해야 할 밀리초. 헤더 없거나 파싱 실패 시 empty.
   */
  public static Optional<Long> parseRetryAfter(HttpHeaders headers) {
    Optional<String> value = headers.firstValue("retry-after");
    if (value.isEmpty()) return Optional.empty();
    String raw = value.get().trim();
    if (raw.isEmpty()) return Optional.empty();

    // delta-seconds 우선 시도
    try {
      long seconds = Long.parseLong(raw);
      return seconds >= 0 ? Optional.of(seconds * 1000L) : Optional.empty();
    } catch (NumberFormatException ignored) {
      // HTTP-date로 fallback
    }

    try {
      ZonedDateTime when = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME);
      long deltaMs = java.time.Duration.between(ZonedDateTime.now(when.getZone()), when).toMillis();
      return Optional.of(Math.max(0L, deltaMs));
    } catch (Exception ignored) {
      return Optional.empty();
    }
  }
}
