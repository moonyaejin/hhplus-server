package kr.hhplus.be.server.domain.queue.model;

import java.util.UUID;

/**
 * 대기열 토큰 Value Object
 */
public record QueueToken(String value) {

    public QueueToken {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("토큰 값은 비어있을 수 없습니다");
        }
    }

    public static QueueToken generate() {
        return new QueueToken(UUID.randomUUID().toString());
    }

    public static QueueToken of(String value) {
        return new QueueToken(value);
    }
}