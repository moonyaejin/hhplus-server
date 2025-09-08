package kr.hhplus.be.server.domain.payment.model;

import java.time.LocalDateTime;

public class Payment {
    private final Long reservationId;
    private final Long amount;
    private final PaymentStatus status;
    private final LocalDateTime paidAt;

    // 기본 생성자 (결제 대기 상태)
    public Payment(Long reservationId, Long amount) {
        this.reservationId = reservationId;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
        this.paidAt = null;
    }

    // 내부적으로 사용하는 전체 생성자
    public Payment(Long reservationId, Long amount, PaymentStatus status, LocalDateTime paidAt) {
        this.reservationId = reservationId;
        this.amount = amount;
        this.status = status;
        this.paidAt = paidAt;
    }

    public Payment complete() {
        return new Payment(this.reservationId, this.amount, PaymentStatus.SUCCESS, LocalDateTime.now());
    }

    public Payment fail() {
        return new Payment(this.reservationId, this.amount, PaymentStatus.FAIL, LocalDateTime.now());
    }

    // getter 추가
    public Long reservationId() { return reservationId; }
    public Long amount() { return amount; }
    public PaymentStatus status() { return status; }
    public LocalDateTime paidAt() { return paidAt; }
}
