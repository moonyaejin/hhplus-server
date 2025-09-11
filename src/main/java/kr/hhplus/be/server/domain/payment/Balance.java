package kr.hhplus.be.server.domain.payment;

public record Balance(long amount) {
    public Balance {
        if (amount < 0) {
            throw new IllegalArgumentException("잔액은 0 이상이어야 합니다");
        }
    }

    public Balance add(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("충전 금액은 0보다 커야 합니다");
        }
        return new Balance(this.amount + amount);
    }

    public Balance subtract(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("사용 금액은 0보다 커야 합니다");
        }
        long result = this.amount - amount;
        if (result < 0) {
            // InsufficientBalanceException 사용 (이미 올바름)
            throw new InsufficientBalanceException("잔액이 부족합니다");
        }
        return new Balance(result);
    }
}