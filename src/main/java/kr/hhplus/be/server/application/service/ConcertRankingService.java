package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.RankingUseCase;
import kr.hhplus.be.server.application.port.out.ConcertSchedulePort;
import kr.hhplus.be.server.application.port.out.RankingPort;
import kr.hhplus.be.server.domain.concert.ConcertSchedule;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConcertRankingService implements RankingUseCase {

    private final RankingPort rankingPort;
    private final ConcertSchedulePort schedulePort;
    private final RedisTemplate<String, String> redisTemplate;

    // Redis Key 상수
    private static final String VELOCITY_PREFIX = "ranking:velocity:";
    private static final String SOLDOUT_RANKING = "ranking:soldout:fastest";
    private static final String SCHEDULE_STATS = "stats:schedule:";
    private static final String TEMP_RANKING = "temp:ranking:";

    // 설정 값
    private static final int DEFAULT_TOTAL_SEATS = 100;  // 기본 좌석 수

    /**
     * 예약 확정 시 호출 - 판매 속도 추적
     */
    @Override
    public void trackReservation(Long scheduleId, int seatCount) {
        String statsKey = SCHEDULE_STATS + scheduleId;

        // 첫 판매 시간 기록 (존재하지 않을 때만)
        Boolean isFirst = redisTemplate.opsForHash()
                .putIfAbsent(statsKey, "startTime", String.valueOf(System.currentTimeMillis()));

        // 판매 수량 누적
        redisTemplate.opsForHash().increment(statsKey, "soldCount", seatCount);

        // 현재 시각 업데이트 (마지막 판매 시각)
        redisTemplate.opsForHash().put(statsKey, "lastSaleTime",
                String.valueOf(System.currentTimeMillis()));

        // 판매 속도 계산 및 랭킹 업데이트
        updateVelocityRanking(scheduleId);

        // 매진 체크
        checkAndRecordSoldOut(scheduleId);
    }

    /**
     * 판매 속도 랭킹 업데이트
     */
    private void updateVelocityRanking(Long scheduleId) {
        String statsKey = SCHEDULE_STATS + scheduleId;
        Map<Object, Object> stats = redisTemplate.opsForHash().entries(statsKey);

        if (stats.isEmpty()) return;

        // 판매 시작 후 경과 시간과 판매량 계산
        long startTime = Long.parseLong((String) stats.get("startTime"));
        Object soldCountObj = stats.get("soldCount");
        long soldCount = soldCountObj instanceof Long ?
                (Long) soldCountObj : Long.parseLong(soldCountObj.toString());

        // 경과 시간 (분 단위)
        long elapsedMinutes = (System.currentTimeMillis() - startTime) / 60000;
        if (elapsedMinutes < 1) elapsedMinutes = 1;  // 최소 1분

        // 분당 판매량 = 판매 속도
        double velocity = soldCount / (double) elapsedMinutes;

        // 시간별 키 사용
        String hourKey = VELOCITY_PREFIX + getCurrentHour();

        // Sorted Set 업데이트 (높은 점수 = 빠른 판매)
        redisTemplate.opsForZSet().add(hourKey, "schedule:" + scheduleId, velocity);

        // 이 시간대 키만 3시간 후 만료
        redisTemplate.expire(hourKey, Duration.ofHours(3));

        log.debug("판매 속도 업데이트 - scheduleId: {}, velocity: {:.2f} tickets/min",
                scheduleId, velocity);
    }

    /**
     * 빠른 판매 랭킹 조회 (판매 중인 공연)
     */
    @Override
    public List<FastSellingDto> getFastSellingRanking(int limit) {
        // 최근 2시간 데이터 합산
        String currentHour = getCurrentHour();
        String previousHour = getPreviousHour();

        Set<String> keys = Set.of(
                VELOCITY_PREFIX + currentHour,
                VELOCITY_PREFIX + previousHour
        );

        // 임시 키로 Union 수행
        String tempKey = TEMP_RANKING + UUID.randomUUID();

        try {
            // 여러 시간대 데이터를 합산
            redisTemplate.opsForZSet().unionAndStore(
                    null,  // 첫 번째 키
                    keys,  // 합칠 키들
                    tempKey
            );

            // 임시 키 짧은 TTL 설정
            redisTemplate.expire(tempKey, Duration.ofSeconds(10));

            // 상위 N개 조회 (높은 점수 순)
            Set<ZSetOperations.TypedTuple<String>> rankings =
                    redisTemplate.opsForZSet().reverseRangeWithScores(tempKey, 0, limit - 1);

            if (rankings == null || rankings.isEmpty()) {
                return List.of();
            }

            // DTO 변환
            List<FastSellingDto> result = new ArrayList<>();
            int rank = 1;

            for (ZSetOperations.TypedTuple<String> tuple : rankings) {
                Long scheduleId = extractScheduleId(tuple.getValue());
                String concertName = getConcertName(scheduleId);

                result.add(new FastSellingDto(
                        rank++,
                        scheduleId,
                        concertName,
                        tuple.getScore() != null ? tuple.getScore() : 0.0
                ));
            }

            return result;

        } finally {
            // 임시 키 정리
            redisTemplate.delete(tempKey);
        }
    }

    /**
     * 매진 속도 랭킹 조회 (빠르게 매진된 공연)
     */
    @Override
    public List<SoldOutRankingDto> getFastestSoldOutRanking(int limit) {
        // 낮은 값이 더 빠른 매진 (초 단위)
        Set<ZSetOperations.TypedTuple<String>> rankings =
                redisTemplate.opsForZSet().rangeWithScores(SOLDOUT_RANKING, 0, limit - 1);

        if (rankings == null || rankings.isEmpty()) {
            return List.of();
        }

        List<SoldOutRankingDto> result = new ArrayList<>();
        int rank = 1;

        for (ZSetOperations.TypedTuple<String> tuple : rankings) {
            Long scheduleId = extractScheduleId(tuple.getValue());
            String concertName = getConcertName(scheduleId);
            int seconds = tuple.getScore() != null ? tuple.getScore().intValue() : 0;

            result.add(new SoldOutRankingDto(
                    rank++,
                    scheduleId,
                    concertName,
                    seconds,
                    formatDuration(seconds)
            ));
        }

        return result;
    }

    /**
     * 매진 확인 및 기록
     */
    private void checkAndRecordSoldOut(Long scheduleId) {
        // 전체 좌석 수 조회
        Integer totalSeats = getTotalSeats(scheduleId);

        String statsKey = SCHEDULE_STATS + scheduleId;
        Object soldCountObj = redisTemplate.opsForHash().get(statsKey, "soldCount");

        if (soldCountObj == null) return;

        long soldCount = soldCountObj instanceof Long ?
                (Long) soldCountObj : Long.parseLong(soldCountObj.toString());

        // 매진 확인
        if (soldCount >= totalSeats) {
            recordSoldOutTime(scheduleId);
        }
    }

    /**
     * 매진 시간 기록
     */
    private void recordSoldOutTime(Long scheduleId) {
        String statsKey = SCHEDULE_STATS + scheduleId;

        // 이미 매진 기록이 있는지 확인
        if (redisTemplate.opsForHash().hasKey(statsKey, "soldOutTime")) {
            return;
        }

        String startTimeStr = (String) redisTemplate.opsForHash().get(statsKey, "startTime");
        if (startTimeStr == null) return;

        long startTime = Long.parseLong(startTimeStr);
        long soldOutTime = System.currentTimeMillis();

        // 매진 소요 시간 (초 단위)
        long durationSeconds = (soldOutTime - startTime) / 1000;

        // 매진 정보 저장
        redisTemplate.opsForHash().put(statsKey, "soldOutTime", String.valueOf(soldOutTime));
        redisTemplate.opsForHash().put(statsKey, "soldOutDuration", String.valueOf(durationSeconds));

        // 매진 속도 랭킹 추가 (낮은 값이 더 빠름)
        redisTemplate.opsForZSet().add(SOLDOUT_RANKING,
                "schedule:" + scheduleId, durationSeconds);

        // 주간 매진 랭킹도 추가
        String weeklyKey = "ranking:soldout:weekly:" + getCurrentWeek();
        redisTemplate.opsForZSet().add(weeklyKey,
                "schedule:" + scheduleId, durationSeconds);
        redisTemplate.expire(weeklyKey, Duration.ofDays(14));  // 2주 보관

        log.info("🎉 매진 기록 - scheduleId: {}, 소요시간: {}초", scheduleId, durationSeconds);

        // 현재 시간대의 판매 속도 랭킹에서 제거
        String currentHourKey = VELOCITY_PREFIX + getCurrentHour();
        redisTemplate.opsForZSet().remove(currentHourKey, "schedule:" + scheduleId);
    }

    // === Helper Methods ===

    /**
     * 현재 시간 키 생성 (YYYYMMDDHH 형식)
     */
    private String getCurrentHour() {
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
    }

    /**
     * 이전 시간 키 생성
     */
    private String getPreviousHour() {
        return LocalDateTime.now()
                .minusHours(1)
                .format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
    }

    /**
     * 현재 주차 키 생성 (YYYY-WXX 형식)
     */
    private String getCurrentWeek() {
        LocalDateTime now = LocalDateTime.now();
        WeekFields weekFields = WeekFields.of(Locale.getDefault());
        int week = now.get(weekFields.weekOfWeekBasedYear());
        int year = now.getYear();
        return String.format("%d-W%02d", year, week);
    }

    /**
     * schedule:123 형식에서 ID 추출
     */
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

    /**
     * 콘서트 이름 조회
     */
    private String getConcertName(Long scheduleId) {
        if (scheduleId == null) {
            return "Unknown";
        }

        try {
            Optional<ConcertSchedule> schedule =
                    schedulePort.findById(new ConcertScheduleId(scheduleId));

            if (schedule.isPresent()) {
                // Concert 정보에서 이름을 가져오거나,
                // 또는 스케줄 정보를 문자열로 표현
                return "Concert #" + scheduleId;  // 실제로는 concert.getName() 등
            }
        } catch (Exception e) {
            log.warn("Failed to get concert name for scheduleId: {}", scheduleId, e);
        }

        return "Concert #" + scheduleId;
    }

    /**
     * 전체 좌석 수 조회
     */
    private Integer getTotalSeats(Long scheduleId) {
        try {
            Optional<ConcertSchedule> schedule =
                    schedulePort.findById(new ConcertScheduleId(scheduleId));

            if (schedule.isPresent()) {
                // 실제로는 schedule.getTotalSeats() 같은 메서드
                return DEFAULT_TOTAL_SEATS;
            }
        } catch (Exception e) {
            log.warn("Failed to get total seats for scheduleId: {}", scheduleId, e);
        }

        return DEFAULT_TOTAL_SEATS;
    }

    /**
     * 시간 포맷팅 (초 → "X분 Y초" 형식)
     */
    private String formatDuration(int seconds) {
        if (seconds < 60) {
            return seconds + "초";
        } else if (seconds < 3600) {
            return String.format("%d분 %d초", seconds / 60, seconds % 60);
        } else {
            return String.format("%d시간 %d분", seconds / 3600, (seconds % 3600) / 60);
        }
    }
}