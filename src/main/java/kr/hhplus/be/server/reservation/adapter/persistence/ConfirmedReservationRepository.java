package kr.hhplus.be.server.reservation.adapter.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface ConfirmedReservationRepository extends JpaRepository<ConfirmedReservationJpaEntity, Long> {

    boolean existsByConcertDateAndSeatNo(LocalDate concertDate, Integer seatNo);
}