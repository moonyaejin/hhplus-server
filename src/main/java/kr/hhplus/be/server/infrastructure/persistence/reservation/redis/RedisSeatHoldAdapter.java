package kr.hhplus.be.server.infrastructure.persistence.reservation.redis;

import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import kr.hhplus.be.server.domain.reservation.SeatHoldStatus;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.reservation.SeatIdentifier;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 기반 좌석 점유 어댑터
 * - 도메인 중심 포트 인터페이스 구현
 * - 기술적 세부사항을 내부로 감춤
 */
@Component
public class RedisSeatHoldAdapter implements SeatHoldPort {

    private final StringRedisTemplate redis;

    // 생성자 추가
    public RedisSeatHoldAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryHold(SeatIdentifier seatIdentifier, UserId userId, Duration holdDuration) {
        String key = buildRedisKey(seatIdentifier);
        String value = buildRedisValue(userId);

        Boolean success = redis.opsForValue().setIfAbsent(key, value, holdDuration);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public boolean isHeldBy(SeatIdentifier seatIdentifier, UserId userId) {
        String key = buildRedisKey(seatIdentifier);
        String storedValue = redis.opsForValue().get(key);
        String expectedValue = buildRedisValue(userId);

        return expectedValue.equals(storedValue);
    }

    @Override
    public void release(SeatIdentifier seatIdentifier) {
        String key = buildRedisKey(seatIdentifier);
        redis.delete(key);
    }

    @Override
    public SeatHoldStatus getHoldStatus(SeatIdentifier seatIdentifier) {
        String key = buildRedisKey(seatIdentifier);
        String value = redis.opsForValue().get(key);

        if (value == null) {
            return null; // 점유되지 않음
        }

        // TTL 조회
        Long ttlSeconds = redis.getExpire(key);
        if (ttlSeconds == null || ttlSeconds <= 0) {
            return null; // 만료됨
        }

        // Redis 값에서 UserId 추출
        UserId holderId = extractUserIdFromValue(value);

        // 대략적인 시간 계산 (정확한 시간은 별도 저장 필요)
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(ttlSeconds);
        LocalDateTime heldAt = expiresAt.minusMinutes(5); // 기본 점유 시간 5분 가정

        return new SeatHoldStatus(holderId, heldAt, expiresAt);
    }

    @Override
    public Map<SeatIdentifier, SeatHoldStatus> getHoldStatusBulk(List<SeatIdentifier> seatIdentifiers) {
        if (seatIdentifiers == null || seatIdentifiers.isEmpty()) {
            return Collections.emptyMap();
        }

        // 1. Redis 키 목록 준비
        List<String> keys = seatIdentifiers.stream()
                .map(this::buildRedisKey)
                .toList();

        // 2. 파이프라인으로 값과 TTL을 한번에 조회
        List<Object> pipelineResults = redis.executePipelined(
                (RedisCallback<Object>) connection -> {
                    for (String key : keys) {
                        connection.stringCommands().get(key.getBytes());  // 값 조회
                        connection.ttl(key.getBytes());  // TTL 조회 (초 단위)
                    }
                    return null;
                }
        );

        // 3. 결과 파싱 (값과 TTL이 번갈아 나옴)
        Map<SeatIdentifier, SeatHoldStatus> resultMap = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < seatIdentifiers.size(); i++) {
            int resultIndex = i * 2;  // 각 키당 2개의 결과 (value, ttl)

            // Redis 값 (byte[]로 받아서 String으로 변환)
            byte[] valueBytes = (byte[]) pipelineResults.get(resultIndex);
            String value = valueBytes != null ? new String(valueBytes) : null;

            // TTL (초 단위)
            Long ttlSeconds = (Long) pipelineResults.get(resultIndex + 1);

            if (value != null && ttlSeconds != null && ttlSeconds > 0) {
                // 점유된 좌석만 맵에 추가
                UserId holderId = extractUserIdFromValue(value);
                LocalDateTime expiresAt = now.plusSeconds(ttlSeconds);

                // 더 정확한 heldAt 계산
                // 5분(300초) 점유 시간 기준으로 역산
                long elapsedSeconds = 300 - ttlSeconds;
                LocalDateTime heldAt = now.minusSeconds(elapsedSeconds);

                SeatHoldStatus status = new SeatHoldStatus(holderId, heldAt, expiresAt);
                resultMap.put(seatIdentifiers.get(i), status);
            }
        }

        return resultMap;
    }

    // === Private Helper Methods ===
    // 기술적 세부사항을 캡슐화

    private String buildRedisKey(SeatIdentifier seatIdentifier) {
        return String.format("seat:hold:%d:%d",
                seatIdentifier.scheduleId().value(),
                seatIdentifier.seatNumber().value());
    }

    private String buildRedisValue(UserId userId) {
        // 추가 정보가 필요하면 JSON 형태로 저장 가능
        return userId.asString();
    }

    private UserId extractUserIdFromValue(String value) {
        // JSON 파싱이 필요하면 여기서 처리
        return UserId.ofString(value);
    }

    // 더 정확한 시간 저장이 필요한 경우 사용할 메서드들
    private String buildRedisValueWithTimestamp(UserId userId) {
        return String.format("%s:%d", userId.asString(), System.currentTimeMillis());
    }

    private UserId extractUserIdFromValueWithTimestamp(String value) {
        if (value.contains(":")) {
            return UserId.ofString(value.substring(0, value.lastIndexOf(":")));
        }
        return UserId.ofString(value);
    }

    private long extractTimestampFromValue(String value) {
        if (value.contains(":")) {
            String timestamp = value.substring(value.lastIndexOf(":") + 1);
            return Long.parseLong(timestamp);
        }
        return 0;
    }
}