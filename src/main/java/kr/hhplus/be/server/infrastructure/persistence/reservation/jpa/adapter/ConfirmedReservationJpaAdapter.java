package kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.adapter;

// 도메인에서 정의한 출력 포트(ConfirmedReservationPort)를 구현해서 JPA Repository를 감싸는 역할

import kr.hhplus.be.server.application.port.out.ConfirmedReservationPort;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.ConfirmedReservationJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.entity.ConfirmedReservationJpaEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ConfirmedReservationJpaAdapter implements ConfirmedReservationPort {

    private final ConfirmedReservationJpaRepository repository;

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

    @Override
    public List<Integer> findSeatNosByConcertDate(LocalDate date) {
        // 기존 JPA Repository에 이미 이 메서드가 있는지 확인하세요
        return repository.findSeatNosByConcertDate(date);
    }
}