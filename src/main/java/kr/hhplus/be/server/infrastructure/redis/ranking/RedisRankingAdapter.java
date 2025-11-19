package kr.hhplus.be.server.infrastructure.redis.ranking;

import kr.hhplus.be.server.application.port.out.RankingPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRankingAdapter implements RankingPort {

    private final RedisTemplate<String, String> redisTemplate;

    // Redis Key 상수 추가
    private static final String VELOCITY_RANKING = "ranking:velocity:current";
    private static final String SOLDOUT_RANKING = "ranking:soldout:fastest";
    private static final String SCHEDULE_STATS = "stats:schedule:";

    @Override
    public void saveStats(String scheduleId, Map<String, String> stats) {
        String key = SCHEDULE_STATS + scheduleId;
        redisTemplate.opsForHash().putAll(key, stats);
    }

    @Override
    public Map<String, String> getStats(String scheduleId) {
        String key = SCHEDULE_STATS + scheduleId;
        Map<Object, Object> hashEntries = redisTemplate.opsForHash().entries(key);

        // Object를 String으로 변환
        Map<String, String> result = new HashMap<>();
        hashEntries.forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }

    @Override
    public long incrementSoldCount(String scheduleId, int increment) {
        String key = SCHEDULE_STATS + scheduleId;

        // Redis HINCRBY는 atomic 연산
        Long newCount = redisTemplate.opsForHash().increment(key, "soldCount", increment);

        // lastSaleTime도 함께 업데이트
        redisTemplate.opsForHash().put(key, "lastSaleTime",
                String.valueOf(System.currentTimeMillis()));

        return newCount != null ? newCount : increment;
    }

    @Override
    public long decrementSoldCount(String scheduleId, int decrement) {
        String key = SCHEDULE_STATS + scheduleId;

        // Redis HINCRBY로 음수 값을 전달하여 차감 (atomic 연산)
        Long newCount = redisTemplate.opsForHash().increment(key, "soldCount", -decrement);

        // lastCancelTime 업데이트
        redisTemplate.opsForHash().put(key, "lastCancelTime",
                String.valueOf(System.currentTimeMillis()));

        return newCount != null ? Math.max(newCount, 0) : 0;  // 음수 방지
    }

    @Override
    public boolean setStartTimeIfAbsent(String scheduleId, long startTime) {
        String key = SCHEDULE_STATS + scheduleId;

        // Redis HSETNX는 atomic 연산 (없을 때만 set)
        Boolean success = redisTemplate.opsForHash().putIfAbsent(key, "startTime", String.valueOf(startTime));

        return Boolean.TRUE.equals(success);
    }

    @Override
    public void updateVelocityRanking(String scheduleId, double score) {
        redisTemplate.opsForZSet().add(VELOCITY_RANKING, "schedule:" + scheduleId, score);
    }

    @Override
    public void updateSoldOutRanking(String scheduleId, long seconds) {
        redisTemplate.opsForZSet().add(SOLDOUT_RANKING, "schedule:" + scheduleId, (double) seconds);
    }

    @Override
    public Set<String> getTopByVelocity(int limit) {
        Set<String> result = redisTemplate.opsForZSet().reverseRange(VELOCITY_RANKING, 0, limit - 1);

        return result != null ? result : Set.of();
    }

    @Override
    public Set<String> getTopBySoldOut(int limit) {
        Set<String> result = redisTemplate.opsForZSet().range(SOLDOUT_RANKING, 0, limit - 1);

        // null 체크 후 반환
        return result != null ? result : Set.of();
    }

    @Override
    public void removeFromVelocityRanking(String scheduleId) {
        redisTemplate.opsForZSet().remove(VELOCITY_RANKING, "schedule:" + scheduleId);
        log.debug("랭킹에서 제거 - scheduleId: {}", scheduleId);
    }
}