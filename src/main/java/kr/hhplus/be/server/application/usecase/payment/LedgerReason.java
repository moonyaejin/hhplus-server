package kr.hhplus.be.server.application.usecase.payment;

public enum LedgerReason {
    CHARGE,   // 충전
    PAYMENT,  // 결제 차감
    REFUND    // 환불 가산
}
