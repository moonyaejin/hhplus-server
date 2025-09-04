package kr.hhplus.be.server.domain.queue;

import kr.hhplus.be.server.domain.queue.model.QueueToken;

import java.time.Duration;
import java.util.Optional;

public interface QueueRepository {

    // 토큰 발급
    QueueToken issue(String userId, Duration ttl);

    // 토큰이 여전히 유효한지
    boolean isActive(QueueToken token);

    // 토큰 소유자 조회
    Optional<String> userIdOf(QueueToken token);

    // 토큰 만료
    void expire(QueueToken token);
}