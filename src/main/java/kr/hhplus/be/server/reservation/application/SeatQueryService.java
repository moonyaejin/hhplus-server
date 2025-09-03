package kr.hhplus.be.server.reservation.application;

import kr.hhplus.be.server.reservation.port.out.ConfirmedReservationPort;
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
    private final ConfirmedReservationPort confirmedReservationPort;
    private final StringRedisTemplate stringRedisTemplate;

    public List<SeatView> seats(LocalDate date) {
        List<SeatView> list = new ArrayList<>();

        Set<Integer> confirmedNos = confirmedSeatNos(date);

        for (int n = 1; n <= 50; n++) {
            if (confirmedNos.contains(n)) {
                list.add(new SeatView(n, "CONFIRMED", null));
                continue;
            }

            Long pttl = stringRedisTemplate.getRequiredConnectionFactory().getConnection()
                    .pTtl(("seat:hold:%s:%d".formatted(date, n)).getBytes());

            if (pttl != null && pttl > 0) {
                list.add(new SeatView(n, "HELD", pttl / 1000));
            } else {
                list.add(new SeatView(n, "FREE", null));
            }
        }
        return list;
    }

    private Set<Integer> confirmedSeatNos(LocalDate date) {
        // TODO: confirmedReservationPort 사용해 구현
        return new HashSet<>();
    }

    public record SeatView(int seatNo, String status, Long remainSec) {}
}
