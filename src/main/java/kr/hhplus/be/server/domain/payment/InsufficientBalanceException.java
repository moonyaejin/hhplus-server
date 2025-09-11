package kr.hhplus.be.server.domain.payment;

/**
 * 지갑 잔액 부족 예외
 */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String message) {
        super(message);
    }

    public InsufficientBalanceException(String message, Throwable cause) {
        super(message, cause);
    }

    // 편의 팩토리 메서드
    public static InsufficientBalanceException of(long requestedAmount, long currentBalance) {
        return new InsufficientBalanceException(
                String.format("잔액이 부족합니다. 요청금액: %,d원, 현재잔액: %,d원",
                        requestedAmount, currentBalance)
        );
    }
}