package kr.hhplus.be.server.reservation.adapter.redis;

import kr.hhplus.be.server.reservation.port.out.QueuePort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RedisQueueAdapter implements QueuePort {
    private final StringRedisTemplate redis;

    private String key(String token) { return "queue:token:%s".formatted(token); }

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

    public void issue(String token, String userId, Duration ttl) {
        redis.opsForValue().set(key(token), userId, ttl);
    }
}
