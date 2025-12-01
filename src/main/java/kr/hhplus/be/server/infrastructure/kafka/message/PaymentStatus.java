package kr.hhplus.be.server.infrastructure.kafka.message;

/**
 * 결제 처리 결과 상태
 */
public enum PaymentStatus {
    SUCCESS("결제 성공"),
    FAILED("결제 실패"),
    INSUFFICIENT_BALANCE("잔액 부족");

    private final String description;

    PaymentStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }
}