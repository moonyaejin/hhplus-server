package kr.hhplus.be.server.infrastructure.persistence.queue.redis;

import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.domain.queue.model.QueueToken;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
public class RedisQueueAdapter implements QueuePort {
    private final StringRedisTemplate redis;
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    public RedisQueueAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(String token) {
        return "queue:token:" + token;
    }

    @Override
    public boolean isActive(String token) {
        return Boolean.TRUE.equals(redis.hasKey(key(token)));
    }

    @Override
    public String userIdOf(String token) {
        return redis.opsForValue().get(key(token));
    }

    @Override
    public void expire(String token) {
        redis.delete(key(token));
    }

    @Override
    public QueueToken issue(String userId) {
        String token = UUID.randomUUID().toString();
        redis.opsForValue().set(key(token), userId, TOKEN_TTL);
        return new QueueToken(token);
    }

    // QueueToken 편의 메서드들 (포트 인터페이스에는 없음)
    public boolean isActiveToken(QueueToken token) {
        return isActive(token.value());
    }

    public String userIdOfToken(QueueToken token) {
        return userIdOf(token.value());
    }

    public void expireToken(QueueToken token) {
        expire(token.value());
    }
}