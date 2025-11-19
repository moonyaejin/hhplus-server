package kr.hhplus.be.server.application.port.in;

import java.time.LocalDateTime;

/**
 * 예약 관련 Use Case 인터페이스
 */
public interface ReservationUseCase {

    // Command 객체들
    record TemporaryAssignCommand(
            String queueToken,
            Long concertScheduleId,
            Integer seatNumber
    ) {}

    record ConfirmReservationCommand(
            String queueToken,
            String reservationId,
            String idempotencyKey
    ) {}

    record ReservationQuery(
            String userId,
            String reservationId
    ) {}

    // Result 객체들
    record TemporaryAssignResult(
            String reservationId,
            long price,
            LocalDateTime expirationTime
    ) {}

    record ConfirmReservationResult(
            String reservationId,
            long remainingBalance,
            LocalDateTime confirmedAt
    ) {}

    record ReservationInfo(
            String reservationId,
            String userId,
            Long concertScheduleId,
            int seatNumber,
            String status,
            long price,
            LocalDateTime temporaryAssignedAt,
            LocalDateTime confirmedAt,
            LocalDateTime expirationTime
    ) {}

    record CancelReservationCommand(
            String queueToken,      // 선택사항 - 없어도 될 수도
            String reservationId,
            String userId,
            String reason           // 취소 사유 (선택)
    ) {}

    record CancelReservationResult(
            String reservationId,
            Long refundAmount,      // 환불 금액
            LocalDateTime cancelledAt
    ) {}

    // Use Case 메서드들
    TemporaryAssignResult temporaryAssign(TemporaryAssignCommand command);
    ConfirmReservationResult confirmReservation(ConfirmReservationCommand command);
    ReservationInfo getReservation(ReservationQuery query);
    CancelReservationResult cancelReservation(CancelReservationCommand command);

}