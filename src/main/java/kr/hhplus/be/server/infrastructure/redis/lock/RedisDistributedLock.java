package kr.hhplus.be.server.infrastructure.redis.lock;

import kr.hhplus.be.server.domain.reservation.SeatIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis 기반 분산락 구현
 * - SETNX를 이용한 락 획득
 * - 락 소유권 확인 후 안전한 락 해제
 * - Retry 로직 포함
 */
@Component
public class RedisDistributedLock {
    private static final String LOCK_PREFIX = "lock:reservation:seat:";

    public static String buildSeatLockKey(SeatIdentifier seatIdentifier) {
        return LOCK_PREFIX +
                seatIdentifier.scheduleId().value() + ":" +
                seatIdentifier.seatNumber().value();
    }
    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);

    private final RedisTemplate<String, String> redisTemplate;

    public RedisDistributedLock(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 락을 획득하고 작업을 실행
     *
     * @param lockKey 락 키
     * @param ttlSeconds 락 유효 시간 (초)
     * @param retryCount 재시도 횟수
     * @param retryDelayMillis 재시도 대기 시간 (밀리초)
     * @param action 실행할 작업
     * @return 작업 결과
     */
    public <T> T executeWithLock(
            String lockKey,
            long ttlSeconds,
            int retryCount,
            long retryDelayMillis,
            Supplier<T> action
    ) {
        String lockValue = UUID.randomUUID().toString();
        int attempts = 0;

        while (attempts < retryCount) {
            boolean acquired = tryLock(lockKey, lockValue, ttlSeconds);

            if (acquired) {
                try {
                    log.debug("락 획득 성공: key={}, value={}", lockKey, lockValue);
                    return action.get();
                } finally {
                    boolean released = unlock(lockKey, lockValue);
                    if (released) {
                        log.debug("락 해제 성공: key={}", lockKey);
                    } else {
                        log.warn("락 해제 실패: key={} (이미 만료되었거나 다른 소유자)", lockKey);
                    }
                }
            }

            attempts++;
            if (attempts < retryCount) {
                log.debug("락 획득 실패, 재시도 {}/{}: key={}", attempts, retryCount, lockKey);
                sleep(retryDelayMillis);
            }
        }

        throw LockAcquisitionException.of(lockKey, retryCount);
    }

    /**
     * 락 획득 시도
     *
     * @param key 락 키
     * @param value 락 값 (소유자 식별용)
     * @param ttlSeconds TTL (초)
     * @return 획득 성공 여부
     */
    public boolean tryLock(String key, String value, long ttlSeconds) {
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(key, value, Duration.ofSeconds(ttlSeconds));
        return Boolean.TRUE.equals(success);
    }

    /**
     * 락 해제 (소유권 확인 후 삭제)
     *
     * @param key 락 키
     * @param value 락 값 (내 락인지 확인용)
     * @return 해제 성공 여부
     */
    public boolean unlock(String key, String value) {
        String currentValue = redisTemplate.opsForValue().get(key);

        // 내 락인지 확인 후 삭제
        if (value.equals(currentValue)) {
            redisTemplate.delete(key);
            return true;
        }

        // 이미 만료되었거나 다른 소유자
        return false;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("락 대기 중 인터럽트 발생", e);
        }
    }
}