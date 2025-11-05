package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.RankingUseCase;
import kr.hhplus.be.server.application.port.out.ConcertSchedulePort;
import kr.hhplus.be.server.application.port.out.RankingPort;
import kr.hhplus.be.server.domain.concert.ConcertSchedule;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConcertRankingService implements RankingUseCase {

    private final ConcertSchedulePort schedulePort;
    private final RankingPort rankingPort;

    private static final int DEFAULT_TOTAL_SEATS = 100;  // 폴백용

    /**
     * 예약 확정 시 호출 - 판매 속도 추적
     */
    @Override
    @CacheEvict(value = "concertRankings", allEntries = true)
    public void trackReservation(Long scheduleId, int seatCount) {
        String scheduleIdStr = String.valueOf(scheduleId);

        // 매진 체크
        Map<String, String> stats = rankingPort.getStats(scheduleIdStr);
        if (stats.containsKey("soldOutTime")) {
            log.debug("이미 매진된 공연 - scheduleId: {}", scheduleId);
            return;
        }

        // 실제 총 좌석 수 조회
        int totalSeats = getTotalSeats(scheduleId, stats);

        // 첫 판매 시간 기록
        rankingPort.setStartTimeIfAbsent(scheduleIdStr, System.currentTimeMillis());

        // 판매 수량 증가
        long newSoldCount = rankingPort.incrementSoldCount(scheduleIdStr, seatCount);

        log.debug("예약 추적 - scheduleId: {}, 추가: {}석, 누적: {}석 / 총: {}석",
                scheduleId, seatCount, newSoldCount, totalSeats);

        // 판매 속도 랭킹 업데이트
        updateVelocityRanking(scheduleId, newSoldCount);

        // 실제 좌석 수 기준 매진 체크
        if (newSoldCount >= totalSeats) {
            recordSoldOut(scheduleId, totalSeats);
        }
    }

    /**
     * 총 좌석 수 조회 (캐싱)
     *
     * 1순위: Redis에 저장된 값
     * 2순위: DB 조회 후 Redis에 캐싱
     * 3순위: 기본값 (폴백)
     */
    private int getTotalSeats(Long scheduleId, Map<String, String> stats) {
        // 1. Redis에서 조회
        String totalSeatsStr = stats.get("totalSeats");
        if (totalSeatsStr != null) {
            try {
                return Integer.parseInt(totalSeatsStr);
            } catch (NumberFormatException e) {
                log.warn("totalSeats 파싱 실패 - scheduleId: {}, value: {}",
                        scheduleId, totalSeatsStr);
            }
        }

        // 2. DB에서 조회
        try {
            Optional<ConcertSchedule> schedule =
                    schedulePort.findById(new ConcertScheduleId(scheduleId));

            if (schedule.isPresent()) {
                int totalSeats = schedule.get().getTotalSeats();

                // Redis에 캐싱
                Map<String, String> cache = Map.of("totalSeats", String.valueOf(totalSeats));
                rankingPort.saveStats(String.valueOf(scheduleId), cache);

                log.debug("totalSeats DB 조회 및 캐싱 - scheduleId: {}, seats: {}",
                        scheduleId, totalSeats);
                return totalSeats;
            }
        } catch (Exception e) {
            log.error("스케줄 조회 실패 - scheduleId: {}", scheduleId, e);
        }

        // 3. 기본값 (폴백)
        log.warn("totalSeats 조회 실패, 기본값 사용 - scheduleId: {}, default: {}",
                scheduleId, DEFAULT_TOTAL_SEATS);
        return DEFAULT_TOTAL_SEATS;
    }

    /**
     * 판매 속도 랭킹 업데이트
     */

    private void updateVelocityRanking(Long scheduleId, long soldCount) {
        Map<String, String> stats = rankingPort.getStats(String.valueOf(scheduleId));
        String startTimeStr = stats.get("startTime");

        if (startTimeStr == null) {
            log.warn("startTime이 없음 - scheduleId: {}", scheduleId);
            return;
        }

        try {
            long startTime = Long.parseLong(startTimeStr);
            long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;

            // 판매 시작 후 1초 미만은 1초로 간주 (0 나누기 방지)
            if (elapsedSeconds < 1) {
                elapsedSeconds = 1;
            }

            // 초당 판매량 = 판매 속도
            double velocity = soldCount / (double) elapsedSeconds;

            rankingPort.updateVelocityRanking(String.valueOf(scheduleId), velocity);
            log.debug("velocity 업데이트 - scheduleId: {}, velocity: {:.2f} 장/초",
                    scheduleId, velocity);

        } catch (NumberFormatException e) {
            log.error("startTime 파싱 실패 - scheduleId: {}", scheduleId, e);
        }
    }

    /**
     * 매진 기록
     */
    private void recordSoldOut(Long scheduleId, int totalSeats) {
        Map<String, String> stats = rankingPort.getStats(String.valueOf(scheduleId));
        String startTimeStr = stats.get("startTime");

        if (startTimeStr == null) {
            log.warn("startTime이 없음 - scheduleId: {}", scheduleId);
            return;
        }

        // 이미 매진 기록이 있는지 재확인 (동시성)
        if (stats.containsKey("soldOutTime")) {
            log.debug("이미 매진 기록됨 - scheduleId: {}", scheduleId);
            return;
        }

        try {
            long startTime = Long.parseLong(startTimeStr);
            long soldOutTime = System.currentTimeMillis();
            long durationSeconds = (soldOutTime - startTime) / 1000;

            // 매진 정보 저장
            Map<String, String> soldOutInfo = Map.of(
                    "soldOutTime", String.valueOf(soldOutTime),
                    "soldOutSeconds", String.valueOf(durationSeconds)
            );

            rankingPort.saveStats(String.valueOf(scheduleId), soldOutInfo);
            rankingPort.updateSoldOutRanking(String.valueOf(scheduleId), durationSeconds);

            log.info("매진 기록 - scheduleId: {}, 좌석: {}석, 소요 시간: {}초",
                    scheduleId, totalSeats, durationSeconds);
        } catch (NumberFormatException e) {
            log.error("매진 기록 실패 - scheduleId: {}, startTime: {}",
                    scheduleId, startTimeStr);
        }
    }

    /**
     * 빠른 판매 랭킹 조회
     */
    @Override
    @Cacheable(value = "concertRankings", key = "#limit")
    public List<ConcertRankingDto> getFastSellingRanking(int limit) {
        log.debug("랭킹 조회 - Port를 통한 데이터 조회 (캐시 미스)");

        // Port를 통해 상위 랭킹 조회
        Set<String> topSchedules = rankingPort.getTopByVelocity(limit);

        if (topSchedules.isEmpty()) {
            return List.of();
        }

        // 1. scheduleId 목록 추출
        List<Long> scheduleIds = topSchedules.stream()
                .map(this::extractScheduleId)
                .filter(Objects::nonNull)
                .toList();

        // 2. 배치로 한 번에 조회
        Map<Long, ConcertSchedule> scheduleMap = schedulePort.findAllByIds(scheduleIds);

        // 3. 랭킹 데이터 조합
        List<ConcertRankingDto> result = new ArrayList<>();
        int rank = 1;

        for (String scheduleKey : topSchedules) {
            try {
                Long scheduleId = extractScheduleId(scheduleKey);
                if (scheduleId == null) continue;

                Map<String, String> stats = rankingPort.getStats(String.valueOf(scheduleId));
                if (stats.isEmpty()) continue;

                // 통계 정보 추출
                int soldCount = getIntValue(stats.get("soldCount"));
                double velocity = calculateVelocity(stats);
                boolean isSoldOut = stats.containsKey("soldOutTime");
                Integer soldOutSeconds = isSoldOut ?
                        getIntValue(stats.get("soldOutSeconds")) : null;

                // Map에서 조회
                String concertName = Optional.ofNullable(scheduleMap.get(scheduleId))
                        .map(schedule -> "Concert #" + scheduleId)
                        .orElse("Unknown Concert #" + scheduleId);

                result.add(new ConcertRankingDto(
                        rank++,
                        scheduleId,
                        concertName,
                        soldCount,
                        velocity,
                        isSoldOut,
                        soldOutSeconds
                ));

            } catch (NumberFormatException e) {
                log.warn("숫자 형식 오류 - scheduleKey: {}", scheduleKey);
            } catch (Exception e) {
                log.error("랭킹 항목 처리 실패 - scheduleKey: {}", scheduleKey, e);
            }
        }

        return result;
    }

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

    private double calculateVelocity(Map<String, String> stats) {
        String startTimeStr = stats.get("startTime");
        String soldCountStr = stats.get("soldCount");

        if (startTimeStr == null || soldCountStr == null) return 0.0;

        try {
            long startTime = Long.parseLong(startTimeStr);
            int soldCount = Integer.parseInt(soldCountStr);
            long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;

            // 판매 시작 후 1초 미만은 1초로 간주 (0 나누기 방지)
            if (elapsedSeconds < 1) {
                elapsedSeconds = 1;
            }

            // 초당 판매량 반환
            return soldCount / (double) elapsedSeconds;
        } catch (NumberFormatException e) {
            log.warn("판매 속도 계산 실패 - startTime: {}, soldCount: {}",
                    startTimeStr, soldCountStr);
            return 0.0;
        }
    }

    private int getIntValue(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("정수 변환 실패 - value: {}", value);
            return 0;
        }
    }
}