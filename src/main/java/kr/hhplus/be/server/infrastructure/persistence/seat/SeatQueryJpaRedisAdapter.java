package kr.hhplus.be.server.infrastructure.persistence.seat;

import kr.hhplus.be.server.application.port.out.SeatQueryPort;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertScheduleJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.ConfirmedReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

@Component
@RequiredArgsConstructor
public class SeatQueryJpaRedisAdapter implements SeatQueryPort {

    // 어댑터에서만 인프라 기술에 의존
    private final ConfirmedReservationJpaRepository confirmedRepo;
    private final ConcertScheduleJpaRepository scheduleRepo;
    private final StringRedisTemplate redis;

    @Override
    public List<SeatView> getSeatsStatus(Long concertId, LocalDate date) {
        // 1) 좌석 수를 회차 스케줄에서 조회
        var schedule = scheduleRepo.findByConcertIdAndConcertDate(concertId, date)
                .orElseThrow(() -> new EmptyResultDataAccessException("concert schedule not found", 1));
        int seatCount = schedule.getSeatCount();

        // 2) 확정 좌석 DB 조회
        Set<Integer> confirmed = new HashSet<>(confirmedRepo.findSeatNosByConcertDate(date));

        // 3) Redis 키 준비 (확정 제외한 좌석들만 TTL 확인)
        List<String> keys = new ArrayList<>(seatCount);
        for (int n = 1; n <= seatCount; n++) {
            if (!confirmed.contains(n)) {
                keys.add(redisKey(date, n));
            }
        }

        // 4) 파이프라인으로 TTL 일괄 조회
        @SuppressWarnings("unchecked")
        List<Object> pttlsRaw = redis.executePipelined(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    for (String k : keys) {
                        connection.pTtl(k.getBytes());
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
                Long pttl = (pttlsRaw != null && idx < pttlsRaw.size())
                        ? (Long) pttlsRaw.get(idx++)
                        : null;
                if (pttl != null && pttl > 0) {
                    list.add(new SeatView(n, SeatStatus.HELD, pttl / 1000));
                } else {
                    list.add(new SeatView(n, SeatStatus.FREE, null));
                }
            }
        }
        return list;
    }

    @Override
    public List<LocalDate> getAvailableDates(int days) {
        List<LocalDate> dates = new ArrayList<>(days);
        LocalDate d = LocalDate.now();
        for (int i = 0; i < days; i++) {
            dates.add(d.plusDays(i));
        }
        return dates;
    }

    private String redisKey(LocalDate date, int seatNo) {
        return "hold:%s:%d".formatted(date, seatNo);
    }
}