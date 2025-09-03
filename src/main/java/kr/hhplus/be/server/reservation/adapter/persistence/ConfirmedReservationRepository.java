package kr.hhplus.be.server.reservation.adapter.persistence;

import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface ConfirmedReservationRepository extends JpaRepository<ConfirmedReservationJpaEntity, Long> {

    boolean existsByConcertDateAndSeatNo(LocalDate concertDate, Integer seatNo);

    @Query("select c.seatNo from ConfirmedReservationJpaEntity c where c.concertDate = :date")
    List<Integer> findSeatNosByConcertDate(@Param("date") LocalDate date);
}