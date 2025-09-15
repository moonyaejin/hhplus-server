package kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository;

import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.entity.ConfirmedReservationJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ConfirmedReservationJpaRepository extends JpaRepository<ConfirmedReservationJpaEntity, Long> {

    boolean existsByConcertDateAndSeatNo(LocalDate concertDate, Integer seatNo);

    @Query("select r.seatNo from ConfirmedReservationJpaEntity r where r.concertDate = :date")
    List<Integer> findSeatNosByConcertDate(@Param("date") LocalDate date);
}