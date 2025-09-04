package kr.hhplus.be.server.application.query;

import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertScheduleRepository;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.ConfirmedReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SeatQueryService {

    private final ConfirmedReservationRepository confirmedRepo;
    private final ConcertScheduleRepository scheduleRepo;
    private final StringRedisTemplate redis;

    /** 좌석 현황 조회: 확정/홀드(남은 TTL)/가용 */
    public List<SeatView> seats(Long concertId, LocalDate date) {
        // 1) 좌석 수를 회차 스케줄에서 조회 (하드코딩 제거)
        var schedule = scheduleRepo.findByConcertIdAndConcertDate(concertId, date)
                .orElseThrow(() -> new EmptyResultDataAccessException("concert schedule not found", 1));
        int seatCount = schedule.getSeatCount();

        // 2) 확정 좌석 DB 조회
        Set<Integer> confirmed = new HashSet<>(confirmedRepo.findSeatNosByConcertDate(date));

        // 3) Redis 키 준비 (확정 제외한 좌석들만 TTL 확인)
        List<Integer> toCheck = new ArrayList<>(seatCount);
        List<String> keys = new ArrayList<>(seatCount);
        for (int n = 1; n <= seatCount; n++) {
            if (!confirmed.contains(n)) {
                toCheck.add(n);
                keys.add(redisKey(date, n)); // hold:{date}:{seatNo}
            }
        }

        // 4) 파이프라인으로 TTL 일괄 조회 (ms 단위)
        @SuppressWarnings("unchecked")
        List<Long> pttls = (List<Long>) redis.executePipelined((connection) -> {
            for (String k : keys) {
                connection.keyCommands().pTtl(k.getBytes()); // PTTL
            }
            return null;
        });

        // 5) 결과 조립
        List<SeatView> list = new ArrayList<>(seatCount);
        int idx = 0;
        for (int n = 1; n <= seatCount; n++) {
            if (confirmed.contains(n)) {
                list.add(new SeatView(n, SeatStatus.CONFIRMED, null));
            } else {
                Long pttl = (pttls != null && idx < pttls.size()) ? pttls.get(idx++) : null;
                if (pttl != null && pttl > 0) {
                    list.add(new SeatView(n, SeatStatus.HELD, pttl / 1000)); // ms → s
                } else {
                    list.add(new SeatView(n, SeatStatus.FREE, null));
                }
            }
        }
        return list;
    }

    /** 간단한 정책: 오늘부터 days일 */
    public List<LocalDate> availableDates(int days) {
        List<LocalDate> dates = new ArrayList<>(days);
        LocalDate d = LocalDate.now();
        for (int i = 0; i < days; i++) dates.add(d.plusDays(i));
        return dates;
    }

    private String redisKey(LocalDate date, int seatNo) {
        // RedisSeatHoldAdapter의 키 규약과 동일하게
        return "hold:%s:%d".formatted(date, seatNo);
    }

    public enum SeatStatus { FREE, HELD, CONFIRMED }
    public record SeatView(int seatNo, SeatStatus status, Long remainSec) {}
}
