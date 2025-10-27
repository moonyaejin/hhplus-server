package kr.hhplus.be.server.infrastructure.redis.ranking;

import kr.hhplus.be.server.application.port.out.RankingPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
    public void updateVelocityRanking(String scheduleId, double score) {
        String hourKey = "ranking:velocity:" + getCurrentHour();
        redisTemplate.opsForZSet().add(hourKey, "schedule:" + scheduleId, score);
    }

    @Override
    public void updateSoldOutRanking(String scheduleId, long seconds) {
        redisTemplate.opsForZSet().add(SOLDOUT_RANKING, "schedule:" + scheduleId, (double) seconds);
    }

    @Override
    public Set<String> getTopByVelocity(int limit) {
        // 현재 시간대 키 사용
        String currentHourKey = "ranking:velocity:" + getCurrentHour();
        Set<String> result = redisTemplate.opsForZSet().reverseRange(currentHourKey, 0, limit - 1);

        // null 체크 후 반환
        return result != null ? result : Set.of();
    }

    @Override
    public Set<String> getTopBySoldOut(int limit) {
        Set<String> result = redisTemplate.opsForZSet().range(SOLDOUT_RANKING, 0, limit - 1);

        // null 체크 후 반환
        return result != null ? result : Set.of();
    }

    // Helper 메서드 추가
    private String getCurrentHour() {
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
    }
}