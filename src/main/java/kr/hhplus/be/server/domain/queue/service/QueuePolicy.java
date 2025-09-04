package kr.hhplus.be.server.domain.queue.service;

import java.time.Duration;

// 대기열 관련 정책 (TTL 등)
public final class QueuePolicy {
    private QueuePolicy() {}

    // 발급된 토큰 유효 시간
    public static final Duration TOKEN_TTL = Duration.ofMinutes(10);
}
