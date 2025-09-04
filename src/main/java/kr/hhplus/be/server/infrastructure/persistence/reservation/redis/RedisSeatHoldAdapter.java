package kr.hhplus.be.server.infrastructure.persistence.reservation.redis;

import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

@Component
public class RedisSeatHoldAdapter implements SeatHoldPort {

    private final StringRedisTemplate redis;

    public RedisSeatHoldAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryHold(LocalDate date, int seatNo, String userId, int holdSeconds) {
        String key = "hold:%s:%s".formatted(date, seatNo);
        Boolean ok = redis.opsForValue().setIfAbsent(key, userId, Duration.ofSeconds(holdSeconds));
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public boolean isHeldBy(LocalDate date, int seatNo, String userId) {
        String key = "hold:%s:%s".formatted(date, seatNo);
        String holder = redis.opsForValue().get(key);
        return userId.equals(holder);
    }

    @Override
    public void release(LocalDate date, int seatNo) {
        String key = "hold:%s:%s".formatted(date, seatNo);
        redis.delete(key);
    }
}
