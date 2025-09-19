package kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository;

import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.entity.SeatHoldJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SeatHoldJpaRepository extends JpaRepository<SeatHoldJpaEntity, Long> {

    Optional<SeatHoldJpaEntity> findByScheduleIdAndSeatNumber(
            Long scheduleId, Integer seatNumber
    );

    List<SeatHoldJpaEntity> findByScheduleIdAndSeatNumberIn(
            Long scheduleId, List<Integer> seatNumbers
    );

    @Modifying
    @Query("DELETE FROM SeatHoldJpaEntity s WHERE s.expiresAt < :now")
    int deleteExpiredHolds(@Param("now") LocalDateTime now);

    @Modifying
    void deleteByScheduleIdAndSeatNumber(Long scheduleId, Integer seatNumber);

    boolean existsByScheduleIdAndSeatNumberAndExpiresAtAfter(
            Long scheduleId, Integer seatNumber, LocalDateTime now
    );
}