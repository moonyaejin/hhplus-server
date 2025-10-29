package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.RankingUseCase;
import kr.hhplus.be.server.application.port.out.ConcertSchedulePort;
import kr.hhplus.be.server.domain.concert.ConcertSchedule;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConcertRankingService implements RankingUseCase {

    private final ConcertSchedulePort schedulePort;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String VELOCITY_RANKING = "ranking:velocity";
    private static final String SCHEDULE_STATS = "stats:schedule:";

    // 설정 값
    private static final int DEFAULT_TOTAL_SEATS = 100;

    /**
     * 예약 확정 시 호출 - 판매 속도 추적
     * 랭킹이 변경되므로 캐시 무효화
     */
    @Override
    @CacheEvict(value = "concertRankings", allEntries = true)
    public void trackReservation(Long scheduleId, int seatCount) {
        String statsKey = SCHEDULE_STATS + scheduleId;

        // 매진 체크 (매진이면 더 이상 집계하지 않음)
        Boolean isSoldOut = redisTemplate.opsForHash().hasKey(statsKey, "soldOutTime");
        if (Boolean.TRUE.equals(isSoldOut)) {
            log.debug("이미 매진된 공연 - scheduleId: {}", scheduleId);
            return;
        }

        // 첫 판매 시간 기록
        redisTemplate.opsForHash().putIfAbsent(statsKey, "startTime",
                String.valueOf(System.currentTimeMillis()));

        // 판매 수량 누적
        Long newSoldCount = redisTemplate.opsForHash().increment(statsKey, "soldCount", seatCount);

        // 현재 시간 업데이트
        redisTemplate.opsForHash().put(statsKey, "lastSaleTime",
                String.valueOf(System.currentTimeMillis()));

        log.debug("예약 추적 - scheduleId: {}, 추가: {}석, 누적: {}석", scheduleId, seatCount, newSoldCount);

        // 판매 속도 계산 및 랭킹 업데이트
        updateVelocityRanking(scheduleId);

        // 매진 체크
        if (newSoldCount >= DEFAULT_TOTAL_SEATS) {
            recordSoldOut(scheduleId);
        }
    }

    /**
     * 판매 속도 랭킹 업데이트
     */
    private void updateVelocityRanking(Long scheduleId) {
        String statsKey = SCHEDULE_STATS + scheduleId;
        Map<Object, Object> stats = redisTemplate.opsForHash().entries(statsKey);

        if (stats.isEmpty()) return;

        long startTime = Long.parseLong((String) stats.get("startTime"));
        Object soldCountObj = stats.get("soldCount");
        long soldCount = soldCountObj instanceof Long ?
                (Long) soldCountObj : Long.parseLong(soldCountObj.toString());

        // 경과 시간 (분 단위)
        long elapsedMinutes = (System.currentTimeMillis() - startTime) / 60000;
        if (elapsedMinutes < 1) elapsedMinutes = 1;

        // 분당 판매량 = 판매 속도
        double velocity = soldCount / (double) elapsedMinutes;

        // Sorted Set 업데이트 (높은 점수 = 빠른 판매)
        redisTemplate.opsForZSet().add(VELOCITY_RANKING, "schedule:" + scheduleId, velocity);

        log.debug("판매 속도 업데이트 - scheduleId: {}, velocity: {:.2f} tickets/min",
                scheduleId, velocity);
    }

    /**
     * 매진 기록
     */
    private void recordSoldOut(Long scheduleId) {
        String statsKey = SCHEDULE_STATS + scheduleId;

        // 이미 매진 기록이 있는지 확인
        if (redisTemplate.opsForHash().hasKey(statsKey, "soldOutTime")) {
            return;
        }

        String startTimeStr = (String) redisTemplate.opsForHash().get(statsKey, "startTime");
        if (startTimeStr == null) return;

        long startTime = Long.parseLong(startTimeStr);
        long soldOutTime = System.currentTimeMillis();
        long durationSeconds = (soldOutTime - startTime) / 1000;

        // 매진 정보 저장
        redisTemplate.opsForHash().put(statsKey, "soldOutTime", String.valueOf(soldOutTime));
        redisTemplate.opsForHash().put(statsKey, "soldOutSeconds", String.valueOf(durationSeconds));

        log.info("🎉 매진 기록 - scheduleId: {}, 소요 시간: {}초", scheduleId, durationSeconds);
    }

    /**
     * 빠른 판매 랭킹 조회 (통합)
     * 조회 결과 캐싱 (10초 TTL)
     */
    @Override
    @Cacheable(value = "concertRankings", key = "#limit")
    public List<ConcertRankingDto> getFastSellingRanking(int limit) {
        log.debug("랭킹 조회 - Redis에서 데이터 조회 (캐시 미스)");

        // 높은 점수 순으로 조회 (빠른 판매 순)
        Set<ZSetOperations.TypedTuple<String>> rankings =
                redisTemplate.opsForZSet().reverseRangeWithScores(VELOCITY_RANKING, 0, limit - 1);

        if (rankings == null || rankings.isEmpty()) {
            return List.of();
        }

        List<ConcertRankingDto> result = new ArrayList<>();
        int rank = 1;

        for (ZSetOperations.TypedTuple<String> tuple : rankings) {
            try {
                Long scheduleId = extractScheduleId(tuple.getValue());
                if (scheduleId == null) continue;

                String statsKey = SCHEDULE_STATS + scheduleId;
                Map<Object, Object> stats = redisTemplate.opsForHash().entries(statsKey);

                if (stats.isEmpty()) continue;

                // 통계 정보 추출
                int soldCount = getIntValue(stats.get("soldCount"));
                double velocity = tuple.getScore() != null ? tuple.getScore() : 0.0;
                boolean isSoldOut = stats.containsKey("soldOutTime");
                Integer soldOutSeconds = isSoldOut ? getIntValue(stats.get("soldOutSeconds")) : null;

                String concertName = getConcertName(scheduleId);

                result.add(new ConcertRankingDto(
                        rank++,
                        scheduleId,
                        concertName,
                        soldCount,
                        velocity,
                        isSoldOut,
                        soldOutSeconds
                ));

            } catch (Exception e) {
                log.error("랭킹 항목 처리 실패 - tuple: {}", tuple, e);
            }
        }

        return result;
    }

    // === Helper Methods ===

    private Long extractScheduleId(String value) {
        if (value == null || !value.startsWith("schedule:")) {
            return null;
        }
        try {
            return Long.parseLong(value.substring("schedule:".length()));
        } catch (NumberFormatException e) {
            log.warn("Invalid schedule ID format: {}", value);
            return null;
        }
    }

    private String getConcertName(Long scheduleId) {
        if (scheduleId == null) {
            return "Unknown";
        }

        try {
            Optional<ConcertSchedule> schedule =
                    schedulePort.findById(new ConcertScheduleId(scheduleId));

            if (schedule.isPresent()) {
                return "Concert #" + scheduleId;
            }
        } catch (Exception e) {
            log.warn("Failed to get concert name for scheduleId: {}", scheduleId, e);
        }

        return "Concert #" + scheduleId;
    }

    private int getIntValue(Object value) {
        if (value == null) return 0;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Long) return ((Long) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}