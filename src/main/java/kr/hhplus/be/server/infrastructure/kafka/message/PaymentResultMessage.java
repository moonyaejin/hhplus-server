package kr.hhplus.be.server.infrastructure.kafka.message;

import java.time.LocalDateTime;

/**
 * 결제 결과 메시지
 *
 * Producer: PaymentRequestConsumer
 * Consumer: PaymentResultConsumer
 * Topic: payment-results
 * Key: reservationId (같은 예약 상태 업데이트 순서 보장)
 */
public record PaymentResultMessage(
        String reservationId,
        String userId,
        PaymentStatus status,
        Long balance,
        String failReason,
        LocalDateTime processedAt
) {
    /**
     * 결제 성공 메시지 생성
     */
    public static PaymentResultMessage success(
            String reservationId,
            String userId,
            Long balance
    ) {
        return new PaymentResultMessage(
                reservationId,
                userId,
                PaymentStatus.SUCCESS,
                balance,
                null,
                LocalDateTime.now()
        );
    }

    /**
     * 결제 실패 메시지 생성
     */
    public static PaymentResultMessage failed(
            String reservationId,
            String userId,
            PaymentStatus status,
            String failReason
    ) {
        return new PaymentResultMessage(
                reservationId,
                userId,
                status,
                null,
                failReason,
                LocalDateTime.now()
        );
    }

    /**
     * 잔액 부족으로 결제 실패
     */
    public static PaymentResultMessage insufficientBalance(
            String reservationId,
            String userId,
            String failReason
    ) {
        return failed(reservationId, userId, PaymentStatus.INSUFFICIENT_BALANCE, failReason);
    }

    public boolean isSuccess() {
        return status.isSuccess();
    }
}