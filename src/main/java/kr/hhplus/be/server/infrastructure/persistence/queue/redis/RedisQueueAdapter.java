package kr.hhplus.be.server.infrastructure.persistence.queue.redis;

import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.domain.queue.model.QueueToken;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Component
public class RedisQueueAdapter implements QueuePort {
    private final StringRedisTemplate redis;
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    public RedisQueueAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(String token) { return "queue:token:" + token; }

    @Override
    public boolean isActive(String token) { return Boolean.TRUE.equals(redis.hasKey(key(token))); }

    @Override
    public String userIdOf(String token) { return redis.opsForValue().get(key(token)); }

    @Override
    public void expire(String token) { redis.delete(key(token)); }

    // 어댑터가 토큰 발급/보관
    @Override
    public QueueToken issue(String userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(key(token), userId, TOKEN_TTL);
        return new QueueToken(token);
    }

    // 도메인 타입 오버로드
    public boolean isActive(QueueToken token) { return isActive(token.value()); }
    public Optional<String> userIdOf(QueueToken token) { return Optional.ofNullable(userIdOf(token.value())); }
    public void expire(QueueToken token) { expire(token.value()); }
}
