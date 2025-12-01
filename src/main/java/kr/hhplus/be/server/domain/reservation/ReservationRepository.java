package kr.hhplus.be.server.domain.reservation;

import kr.hhplus.be.server.domain.common.UserId;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 예약 도메인 리포지토리 인터페이스
 */
public interface ReservationRepository {

    // 기본 CRUD
    Reservation save(Reservation reservation);
    Optional<Reservation> findById(ReservationId id);
    void delete(Reservation reservation);

    // 비즈니스 조회 메서드
    Optional<Reservation> findByUserIdAndSeatIdentifier(UserId userId, SeatIdentifier seatIdentifier);

    // 좌석별 예약 상태 확인 - 단일 상태만 조회
    boolean existsBySeatIdentifierAndStatus(SeatIdentifier seatIdentifier, ReservationStatus status);
    List<Reservation> findBySeatIdentifierAndStatus(SeatIdentifier seatIdentifier, ReservationStatus status);

    // 사용자별 예약 조회
    List<Reservation> findByUserId(UserId userId);
    List<Reservation> findByUserIdAndStatus(UserId userId, ReservationStatus status);

    // 만료된 예약 조회 (배치 작업용)
    List<Reservation> findExpiredTemporaryReservations(LocalDateTime expiredBefore);

    // 특정 콘서트 회차의 예약 조회
    List<Reservation> findByScheduleId(Long scheduleId);
    List<Reservation> findByScheduleIdAndStatus(Long scheduleId, ReservationStatus status);

    // 통계 조회
    long countByStatus(ReservationStatus status);
    long countByScheduleIdAndStatus(Long scheduleId, ReservationStatus status);

    /**
     * 특정 상태이면서 결제 요청 시간이 특정 시간 이전인 예약 조회
     * (결제 타임아웃 처리용)
     *
     * @param status 예약 상태
     * @param paymentRequestedAtBefore 결제 요청 시간 기준
     * @return 조건에 맞는 예약 목록
     */
    List<Reservation> findByStatusAndPaymentRequestedAtBefore(
            ReservationStatus status,
            LocalDateTime paymentRequestedAtBefore
    );
}