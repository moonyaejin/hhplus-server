package kr.hhplus.be.server.infrastructure.redis.queue;

import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.domain.queue.model.QueueToken;
import kr.hhplus.be.server.infrastructure.redis.lock.RedisDistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class RedisQueueAdapter implements QueuePort {

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisDistributedLock distributedLock;

    private static final String WAITING_QUEUE = "queue:waiting";
    private static final String ACTIVE_SET = "queue:active";
    private static final String TOKEN_INFO = "queue:token:";
    private static final String USER_TOKEN_MAP = "queue:user:";
    private static final String LOCK_ACTIVATE = "lock:queue:activate";

    private static final int MAX_ACTIVE_USERS = 100;
    private static final int TOKEN_TTL_MINUTES = 10;

    @Override
    public QueueToken issue(String userId) {
        // 기존 토큰 확인
        String existingToken = redisTemplate.opsForValue().get(USER_TOKEN_MAP + userId);
        if (existingToken != null && isValidToken(existingToken)) {
            return new QueueToken(existingToken);
        }

        // 새 토큰 생성
        String token = UUID.randomUUID().toString();
        long timestamp = Instant.now().toEpochMilli();

        // 토큰 정보 저장
        saveTokenInfo(token, userId, timestamp);
        redisTemplate.opsForValue().set(USER_TOKEN_MAP + userId, token, TOKEN_TTL_MINUTES, TimeUnit.MINUTES);

        // 원자적 카운터로 동시성 제어
        Long currentCount = redisTemplate.opsForValue().increment("queue:active:counter");

        if (currentCount != null && currentCount <= MAX_ACTIVE_USERS) {
            // 100명 이내면 활성화
            activateToken(token);
            log.info("토큰 발급 완료: userId={}, activated=true", userId);
        } else {
            // 초과하면 카운터 롤백하고 대기열 추가
            redisTemplate.opsForValue().decrement("queue:active:counter");
            redisTemplate.opsForZSet().add(WAITING_QUEUE, token, (double) timestamp);
            redisTemplate.opsForHash().put(TOKEN_INFO + token, "status", "WAITING");
            log.info("토큰 발급 완료: userId={}, activated=false", userId);
        }

        return new QueueToken(token);
    }

    private boolean tryActivateWithLock(String token, long timestamp) {
        log.debug("활성화 시도: token={}", token);
        try {
            Boolean result = distributedLock.executeWithLock(
                    LOCK_ACTIVATE, 10, 50, 100,  // TTL 10초, retry 50번, 100ms 대기 = 최대 5초
                    () -> {
                        Long activeCount = redisTemplate.opsForSet().size(ACTIVE_SET);
                        log.debug("현재 활성 사용자 수: {}", activeCount);

                        if (activeCount != null && activeCount < MAX_ACTIVE_USERS) {
                            activateToken(token);
                            log.debug("즉시 활성화: token={}", token);
                            return true;
                        } else {
                            redisTemplate.opsForZSet().add(WAITING_QUEUE, token, (double) timestamp);
                            redisTemplate.opsForHash().put(TOKEN_INFO + token, "status", "WAITING");
                            log.debug("대기열 추가: token={}, activeCount={}", token, activeCount);
                            return false;
                        }
                    }
            );
            log.debug("분산락 실행 결과: token={}, result={}", token, result);
            return result != null && result;
        } catch (Exception e) {
            log.error("분산락 획득 실패, 대기열에 추가: token={}", token, e);
            // 락 획득 실패 시 안전하게 대기열에 추가
            redisTemplate.opsForZSet().add(WAITING_QUEUE, token, (double) timestamp);
            redisTemplate.opsForHash().put(TOKEN_INFO + token, "status", "WAITING");
            return false;
        }
    }

    private void activateToken(String token) {
        redisTemplate.opsForZSet().remove(WAITING_QUEUE, token);
        redisTemplate.opsForSet().add(ACTIVE_SET, token);
        redisTemplate.opsForHash().put(TOKEN_INFO + token, "status", "ACTIVE");
        redisTemplate.expire(TOKEN_INFO + token, TOKEN_TTL_MINUTES, TimeUnit.MINUTES);
    }

    private void saveTokenInfo(String token, String userId, long timestamp) {
        Map<String, String> tokenInfo = new HashMap<>();
        tokenInfo.put("userId", userId);
        tokenInfo.put("issuedAt", String.valueOf(timestamp));
        tokenInfo.put("status", "PENDING");
        redisTemplate.opsForHash().putAll(TOKEN_INFO + token, tokenInfo);
    }

    @Override
    public boolean isActive(String token) {
        Boolean isMember = redisTemplate.opsForSet().isMember(ACTIVE_SET, token);
        return Boolean.TRUE.equals(isMember);
    }

    @Override
    public String userIdOf(String token) {
        Object userId = redisTemplate.opsForHash().get(TOKEN_INFO + token, "userId");
        return userId != null ? userId.toString() : null;
    }

    @Override
    public void expire(String token) {
        String userId = userIdOf(token);

        // 활성 토큰이었는지 확인 (status로 판단)
        Object status = redisTemplate.opsForHash().get(TOKEN_INFO + token, "status");
        boolean wasActive = "ACTIVE".equals(status);

        // 활성 토큰이었다면 카운터와 SET 모두 정리
        if (wasActive) {
            redisTemplate.opsForValue().decrement("queue:active:counter");
            redisTemplate.opsForSet().remove(ACTIVE_SET, token);
        }

        // 대기열과 토큰 정보 정리
        redisTemplate.opsForZSet().remove(WAITING_QUEUE, token);
        redisTemplate.delete(TOKEN_INFO + token);
        if (userId != null) {
            redisTemplate.delete(USER_TOKEN_MAP + userId);
        }
    }

    @Override
    public Long getWaitingPosition(String token) {
        Long rank = redisTemplate.opsForZSet().rank(WAITING_QUEUE, token);
        return rank != null ? rank + 1 : null;
    }

    @Override
    public Long getActiveCount() {
        String counterStr = redisTemplate.opsForValue().get("queue:active:counter");
        if (counterStr == null) {
            return 0L;
        }
        try {
            long counter = Long.parseLong(counterStr);
            return Math.max(0, counter); // 음수 방지
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public Long getWaitingCount() {
        Long count = redisTemplate.opsForZSet().size(WAITING_QUEUE);
        return count != null ? count : 0L;
    }

    @Override
    public void activateNextUsers(int count) {
        if (count <= 0) return;

        try {
            distributedLock.executeWithLock(
                    LOCK_ACTIVATE, 10, 3, 200,
                    () -> {
                        cleanupExpiredTokens();

                        Long activeCount = getActiveCount();
                        int availableSlots = MAX_ACTIVE_USERS - activeCount.intValue();
                        if (availableSlots <= 0) return null;

                        int toActivate = Math.min(count, availableSlots);
                        Set<String> tokens = redisTemplate.opsForZSet().range(WAITING_QUEUE, 0, toActivate - 1);

                        if (tokens != null && !tokens.isEmpty()) {
                            tokens.forEach(this::activateToken);
                            log.info("대기열 활성화: {}명", tokens.size());
                        }
                        return null;
                    }
            );
        } catch (Exception e) {
            log.error("대기열 활성화 실패", e);
        }
    }

    private boolean isValidToken(String token) {
        if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(ACTIVE_SET, token))) {
            return true;
        }
        Double score = redisTemplate.opsForZSet().score(WAITING_QUEUE, token);
        return score != null;
    }

    private void cleanupExpiredTokens() {
        Set<String> activeTokens = redisTemplate.opsForSet().members(ACTIVE_SET);
        if (activeTokens == null || activeTokens.isEmpty()) return;

        int cleaned = 0;
        for (String token : activeTokens) {
            Boolean exists = redisTemplate.hasKey(TOKEN_INFO + token);
            if (!Boolean.TRUE.equals(exists)) {
                redisTemplate.opsForSet().remove(ACTIVE_SET, token);
                cleaned++;
            }
        }
        if (cleaned > 0) {
            log.info("만료 토큰 정리: {}개", cleaned);
        }
    }
}