package kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository;

import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.entity.ReservationJpaEntity;
import kr.hhplus.be.server.domain.reservation.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationJpaRepository extends JpaRepository<ReservationJpaEntity, String> {

    // 단일 조회
    Optional<ReservationJpaEntity> findByUserIdAndConcertScheduleIdAndSeatNumber(
            String userId, Long concertScheduleId, Integer seatNumber);

    // 존재 여부 확인
    boolean existsByConcertScheduleIdAndSeatNumberAndStatus(
            Long concertScheduleId, Integer seatNumber, ReservationStatus status);

    // 좌석별 예약 조회 (단일 상태)
    List<ReservationJpaEntity> findByConcertScheduleIdAndSeatNumberAndStatus(
            Long concertScheduleId, Integer seatNumber, ReservationStatus status);

    // 사용자별 예약 조회
    List<ReservationJpaEntity> findByUserId(String userId);

    List<ReservationJpaEntity> findByUserIdAndStatus(String userId, ReservationStatus status);

    // 스케줄별 예약 조회
    List<ReservationJpaEntity> findByConcertScheduleId(Long concertScheduleId);

    List<ReservationJpaEntity> findByConcertScheduleIdAndStatus(
            Long concertScheduleId, ReservationStatus status);

    // 만료된 예약 조회
    @Query("SELECT r FROM ReservationJpaEntity r " +
            "WHERE r.status = :status " +
            "AND r.temporaryAssignedAt < :expirationTime")
    List<ReservationJpaEntity> findExpiredTemporaryReservations(
            @Param("status") ReservationStatus status,
            @Param("expirationTime") LocalDateTime expirationTime);

    // 통계
    long countByStatus(ReservationStatus status);

    long countByConcertScheduleIdAndStatus(Long concertScheduleId, ReservationStatus status);
}