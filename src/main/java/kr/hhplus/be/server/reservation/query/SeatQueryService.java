// src/main/java/kr/hhplus/be/server/reservation/query/SeatQueryService.java
package kr.hhplus.be.server.reservation.query;

import kr.hhplus.be.server.reservation.adapter.persistence.ConfirmedReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SeatQueryService {

    private final ConfirmedReservationRepository confirmedRepo;
    private final StringRedisTemplate redis;

    public List<SeatView> seats(LocalDate date) {
        List<SeatView> list = new ArrayList<>(50);
        Set<Integer> confirmed = new HashSet<>(confirmedRepo.findSeatNosByConcertDate(date));

        for (int n = 1; n <= 50; n++) {
            if (confirmed.contains(n)) {
                list.add(new SeatView(n, "CONFIRMED", null));
                continue;
            }
            Long pttl = redis.getRequiredConnectionFactory().getConnection()
                    .pTtl(("seat:hold:%s:%d".formatted(date, n)).getBytes());
            if (pttl != null && pttl > 0) {
                list.add(new SeatView(n, "HELD", pttl / 1000));
            } else {
                list.add(new SeatView(n, "FREE", null));
            }
        }
        return list;
    }

    /** 간단한 정책: 오늘부터 7일 */
    public List<LocalDate> availableDates(int days) {
        List<LocalDate> dates = new ArrayList<>(days);
        LocalDate d = LocalDate.now();
        for (int i = 0; i < days; i++) dates.add(d.plusDays(i));
        return dates;
    }

    public record SeatView(int seatNo, String status, Long remainSec) {}
}
