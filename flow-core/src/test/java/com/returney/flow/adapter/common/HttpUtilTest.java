package com.returney.flow.adapter.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HttpUtilTest {

  private static HttpHeaders headers(Map<String, String> entries) {
    Map<String, List<String>> raw = new java.util.HashMap<>();
    entries.forEach((k, v) -> raw.put(k, List.of(v)));
    return HttpHeaders.of(raw, (a, b) -> true);
  }

  @Test
  void retry_after_없으면_empty() {
    Optional<Long> r = HttpUtil.parseRetryAfter(headers(Map.of("Content-Type", "application/json")));
    assertThat(r).isEmpty();
  }

  @Test
  void retry_after_delta_seconds_파싱() {
    Optional<Long> r = HttpUtil.parseRetryAfter(headers(Map.of("Retry-After", "60")));
    assertThat(r).hasValue(60_000L);
  }

  @Test
  void retry_after_0_도_유효() {
    Optional<Long> r = HttpUtil.parseRetryAfter(headers(Map.of("Retry-After", "0")));
    assertThat(r).hasValue(0L);
  }

  @Test
  void retry_after_음수는_empty() {
    Optional<Long> r = HttpUtil.parseRetryAfter(headers(Map.of("Retry-After", "-5")));
    assertThat(r).isEmpty();
  }

  @Test
  void retry_after_HTTP_date_파싱() {
    // 미래 1시간 후
    ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusHours(1);
    String httpDate = future.format(DateTimeFormatter.RFC_1123_DATE_TIME);

    Optional<Long> r = HttpUtil.parseRetryAfter(headers(Map.of("Retry-After", httpDate)));
    assertThat(r).isPresent();
    // 약 1시간(±10초)
    assertThat(r.get()).isBetween(3_590_000L, 3_610_000L);
  }

  @Test
  void retry_after_과거_HTTP_date는_0() {
    ZonedDateTime past = ZonedDateTime.now(ZoneOffset.UTC).minusHours(1);
    String httpDate = past.format(DateTimeFormatter.RFC_1123_DATE_TIME);

    Optional<Long> r = HttpUtil.parseRetryAfter(headers(Map.of("Retry-After", httpDate)));
    assertThat(r).hasValue(0L);
  }

  @Test
  void retry_after_파싱_불가는_empty() {
    Optional<Long> r = HttpUtil.parseRetryAfter(headers(Map.of("Retry-After", "not-a-date-or-number")));
    assertThat(r).isEmpty();
  }

  @Test
  void retry_after_헤더명은_case_insensitive() {
    // HTTP 헤더는 case-insensitive — HttpHeaders.firstValue로 처리됨
    Optional<Long> r = HttpUtil.parseRetryAfter(headers(Map.of("retry-after", "30")));
    assertThat(r).hasValue(30_000L);
  }
}
