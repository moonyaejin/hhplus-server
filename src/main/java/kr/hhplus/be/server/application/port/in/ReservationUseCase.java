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


    /**
     * 예약 확정 결과
     *
     * [비동기 결제 변경사항]
     * - remainingBalance: Long (nullable) - 비동기 결제 시 null 반환
     * - confirmedAt: LocalDateTime (nullable) - 비동기 결제 시 null 반환
     * - status 필드 추가 - 현재 예약 상태 확인용
     */
    record ConfirmReservationResult(
            String reservationId,
            Long remainingBalance,      // long → Long (비동기 결제 시 null)
            LocalDateTime confirmedAt,  // 비동기 결제 시 null
            String status               // 추가: PAYMENT_PENDING, CONFIRMED 등
    ) {
        /**
         * 동기 결제용 생성자 (기존 호환)
         */
        public ConfirmReservationResult(String reservationId, long remainingBalance, LocalDateTime confirmedAt) {
            this(reservationId, remainingBalance, confirmedAt, "CONFIRMED");
        }

        /**
         * 비동기 결제용 생성자
         */
        public static ConfirmReservationResult pending(String reservationId) {
            return new ConfirmReservationResult(reservationId, null, null, "PAYMENT_PENDING");
        }

        /**
         * 결제 진행 중 여부
         */
        public boolean isPaymentPending() {
            return "PAYMENT_PENDING".equals(status);
        }
    }

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
            String queueToken,
            String reservationId,
            String userId,
            String reason
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