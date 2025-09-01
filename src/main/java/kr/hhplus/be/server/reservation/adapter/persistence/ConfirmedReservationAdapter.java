package kr.hhplus.be.server.reservation.adapter.persistence;

// 도메인에서 정의한 출력 포트(ConfirmedReservationPort)를 구현해서 JPA Repository를 감싸는 역할

import kr.hhplus.be.server.reservation.port.out.ConfirmedReservationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ConfirmedReservationAdapter implements ConfirmedReservationPort {

    private final ConfirmedReservationRepository repository;

    @Override
    public boolean exists(LocalDate date, int seatNo) {
        return repository.existsByConcertDateAndSeatNo(date, seatNo);
    }

    @Override
    public long insert(LocalDate date, int seatNo, String userId, long price, Instant paidAt) {
        ConfirmedReservationJpaEntity entity = new ConfirmedReservationJpaEntity(
                UUID.fromString(userId),
                date,
                seatNo,
                price,
                paidAt
        );
        return repository.save(entity).getId();
    }
}
