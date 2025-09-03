package kr.hhplus.be.server.reservation.adapter.redis;

import kr.hhplus.be.server.reservation.port.out.SeatHoldPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class RedisSeatHoldAdapter implements SeatHoldPort {
    private final StringRedisTemplate redis;

    // yyyy-MM-dd
    private String key(LocalDate date, int seatNo) {
        return "seat:hold:%s:%d".formatted(date, seatNo);
    }

    @Override
    public boolean tryHold(LocalDate date, int seatNo, String userId, int ttlSec) {
        Boolean ok = redis.opsForValue()
                .setIfAbsent(key(date, seatNo), userId, Duration.ofSeconds(ttlSec));
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public boolean isHeldBy(LocalDate date, int seatNo, String userId) {
        String v = redis.opsForValue().get(key(date, seatNo));
        return userId.equals(v);
    }

    @Override
    public void release(LocalDate date, int seatNo) {
        redis.delete(key(date, seatNo));
    }
}
