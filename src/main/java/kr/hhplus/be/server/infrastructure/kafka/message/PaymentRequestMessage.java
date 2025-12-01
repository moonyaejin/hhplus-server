package kr.hhplus.be.server.infrastructure.kafka.message;

import java.time.LocalDateTime;

/**
 * 결제 요청 메시지
 *
 * Producer: ReservationService
 * Consumer: PaymentRequestConsumer
 * Topic: payment-requests
 * Key: userId (같은 사용자 결제 순서 보장)
 */
public record PaymentRequestMessage(
        String reservationId,
        String userId,
        Long amount,
        String idempotencyKey,
        Long scheduleId,
        Integer seatNumber,
        LocalDateTime requestedAt
) {
    /**
     * 예약 정보로부터 결제 요청 메시지 생성
     */
    public static PaymentRequestMessage of(
            String reservationId,
            String userId,
            Long amount,
            String idempotencyKey,
            Long scheduleId,
            Integer seatNumber
    ) {
        return new PaymentRequestMessage(
                reservationId,
                userId,
                amount,
                idempotencyKey,
                scheduleId,
                seatNumber,
                LocalDateTime.now()
        );
    }
}