package kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository;

import jakarta.persistence.LockModeType;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.entity.SeatHoldJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    // 비관적 락 메서드 추가 - 동시성 제어용
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SeatHoldJpaEntity s " +
            "WHERE s.scheduleId = :scheduleId " +
            "AND s.seatNumber = :seatNumber")
    Optional<SeatHoldJpaEntity> findByScheduleIdAndSeatNumberWithLock(
            @Param("scheduleId") Long scheduleId,
            @Param("seatNumber") Integer seatNumber
    );

    List<SeatHoldJpaEntity> findByScheduleIdAndSeatNumberIn(
            Long scheduleId, List<Integer> seatNumbers
    );

    // 만료된 모든 홀드 삭제
    @Modifying
    @Query("DELETE FROM SeatHoldJpaEntity s WHERE s.expiresAt < :now")
    int deleteExpiredHolds(@Param("now") LocalDateTime now);

    // 특정 좌석의 만료된 홀드 삭제
    @Modifying
    @Query("DELETE FROM SeatHoldJpaEntity s " +
            "WHERE s.scheduleId = :scheduleId " +
            "AND s.seatNumber = :seatNumber " +
            "AND s.expiresAt <= :now")
    int deleteExpiredHold(
            @Param("scheduleId") Long scheduleId,
            @Param("seatNumber") Integer seatNumber,
            @Param("now") LocalDateTime now
    );

    @Modifying
    void deleteByScheduleIdAndSeatNumber(Long scheduleId, Integer seatNumber);

    boolean existsByScheduleIdAndSeatNumberAndExpiresAtAfter(
            Long scheduleId, Integer seatNumber, LocalDateTime now
    );
}