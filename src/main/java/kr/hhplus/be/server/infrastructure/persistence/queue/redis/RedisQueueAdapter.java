package kr.hhplus.be.server.infrastructure.persistence.queue.redis;

import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.domain.queue.model.QueueToken;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class RedisQueueAdapter implements QueuePort {
    private final StringRedisTemplate redis;
    private static final String WAITING_QUEUE_KEY = "queue:waiting";
    private static final String ACTIVE_TOKENS_KEY = "queue:active";
    private static final String TOKEN_USER_KEY_PREFIX = "queue:token:";
    private static final String USER_TOKEN_KEY_PREFIX = "queue:user:";  // 사용자별 토큰 매핑 추가
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);
    private static final int MAX_ACTIVE_USERS = 100;

    public RedisQueueAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public QueueToken issue(String userId) {
        String token = UUID.randomUUID().toString();

        // Lua 스크립트로 원자적 처리
        String script = """
            local activeKey = KEYS[1]
            local waitingKey = KEYS[2]
            local tokenKey = KEYS[3]
            local userTokenKey = KEYS[4]
            local token = ARGV[1]
            local userId = ARGV[2]
            local maxActive = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])
            local timestamp = ARGV[5]
            
            local activeCount = redis.call('SCARD', activeKey)
            
            if activeCount < maxActive then
                redis.call('SADD', activeKey, token)
                redis.call('SETEX', tokenKey, ttl, userId)
                redis.call('SET', userTokenKey, token)
                return 'ACTIVE'
            else
                redis.call('ZADD', waitingKey, timestamp, token)
                redis.call('SET', tokenKey, userId)
                redis.call('SET', userTokenKey, token)
                return 'WAITING'
            end
        """;

        DefaultRedisScript<String> redisScript = new DefaultRedisScript<>(script, String.class);

        String status = redis.execute(
                redisScript,
                Arrays.asList(
                        ACTIVE_TOKENS_KEY,
                        WAITING_QUEUE_KEY,
                        TOKEN_USER_KEY_PREFIX + token,
                        USER_TOKEN_KEY_PREFIX + userId
                ),
                token,
                userId,
                String.valueOf(MAX_ACTIVE_USERS),
                String.valueOf(TOKEN_TTL.getSeconds()),
                String.valueOf(Instant.now().toEpochMilli())
        );

        return new QueueToken(token);
    }

    @Override
    public boolean isActive(String token) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(ACTIVE_TOKENS_KEY, token));
    }

    @Override
    public String userIdOf(String token) {
        return redis.opsForValue().get(TOKEN_USER_KEY_PREFIX + token);
    }

    @Override
    public void expire(String token) {
        String userId = redis.opsForValue().get(TOKEN_USER_KEY_PREFIX + token);

        // 활성 토큰에서 제거
        redis.opsForSet().remove(ACTIVE_TOKENS_KEY, token);
        // 대기열에서도 제거
        redis.opsForZSet().remove(WAITING_QUEUE_KEY, token);
        // 토큰 정보 삭제
        redis.delete(TOKEN_USER_KEY_PREFIX + token);
        // 사용자-토큰 매핑 삭제
        if (userId != null) {
            redis.delete(USER_TOKEN_KEY_PREFIX + userId);
        }
    }

    // 사용자 ID로 토큰 찾기
    public String getTokenByUserId(String userId) {
        return redis.opsForValue().get(USER_TOKEN_KEY_PREFIX + userId);
    }

    // 대기 순번 조회
    public Long getWaitingPosition(String token) {
        Long rank = redis.opsForZSet().rank(WAITING_QUEUE_KEY, token);
        return rank != null ? rank + 1 : null;
    }

    // 대기 중인 토큰 수 조회
    public Long getWaitingCount() {
        Long size = redis.opsForZSet().size(WAITING_QUEUE_KEY);
        return size != null ? size : 0L;
    }

    // 활성 토큰 수 조회
    public Long getActiveCount() {
        Long size = redis.opsForSet().size(ACTIVE_TOKENS_KEY);
        return size != null ? size : 0L;
    }

    // 대기열에서 N명 활성화 (원자적 처리)
    public void activateNextUsers(int count) {
        String script = """
            local waitingKey = KEYS[1]
            local activeKey = KEYS[2]
            local count = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[2])
            
            local tokens = redis.call('ZRANGE', waitingKey, 0, count - 1)
            
            for i, token in ipairs(tokens) do
                local userIdKey = 'queue:token:' .. token
                local userId = redis.call('GET', userIdKey)
                
                if userId then
                    redis.call('ZADD', activeKey, token)
                    redis.call('EXPIRE', userIdKey, ttl)
                    redis.call('ZREM', waitingKey, token)
                end
            end
            
            return #tokens
        """;

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);

        redis.execute(
                redisScript,
                Arrays.asList(WAITING_QUEUE_KEY, ACTIVE_TOKENS_KEY),
                String.valueOf(count),
                String.valueOf(TOKEN_TTL.getSeconds())
        );
    }
}