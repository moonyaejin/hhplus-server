package kr.hhplus.be.server.domain.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 금액을 나타내는 Value Object
 * 불변 객체로 구현하여 안전한 금액 계산을 보장
 */
public final class Money {

    private final long amount; // 원 단위 (정수)

    public Money(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("금액은 0 이상이어야 합니다");
        }
        this.amount = amount;
    }

    public static Money zero() {
        return new Money(0L);
    }

    public static Money of(long amount) {
        return new Money(amount);
    }

    public static Money of(BigDecimal amount) {
        if (amount.scale() > 0) {
            throw new IllegalArgumentException("소수점이 있는 금액은 지원하지 않습니다");
        }
        return new Money(amount.longValue());
    }

    // 금액 연산
    public Money add(Money other) {
        return new Money(this.amount + other.amount);
    }

    public Money add(long amount) {
        return new Money(this.amount + amount);
    }

    public Money subtract(Money other) {
        long result = this.amount - other.amount;
        if (result < 0) {
            throw new IllegalArgumentException("결과 금액이 음수가 될 수 없습니다");
        }
        return new Money(result);
    }

    public Money subtract(long amount) {
        long result = this.amount - amount;
        if (result < 0) {
            throw new IllegalArgumentException("결과 금액이 음수가 될 수 없습니다");
        }
        return new Money(result);
    }

    public Money multiply(int multiplier) {
        if (multiplier < 0) {
            throw new IllegalArgumentException("곱셈 인수는 0 이상이어야 합니다");
        }
        return new Money(this.amount * multiplier);
    }

    public Money multiply(BigDecimal multiplier) {
        if (multiplier.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("곱셈 인수는 0 이상이어야 합니다");
        }
        BigDecimal result = BigDecimal.valueOf(this.amount)
                .multiply(multiplier)
                .setScale(0, RoundingMode.HALF_UP);
        return new Money(result.longValue());
    }

    // 비교 연산
    public boolean isGreaterThan(Money other) {
        return this.amount > other.amount;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return this.amount >= other.amount;
    }

    public boolean isLessThan(Money other) {
        return this.amount < other.amount;
    }

    public boolean isLessThanOrEqual(Money other) {
        return this.amount <= other.amount;
    }

    public boolean isZero() {
        return this.amount == 0;
    }

    public boolean isPositive() {
        return this.amount > 0;
    }

    // Getter
    public long amount() {
        return amount;
    }

    // Object methods
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Money money = (Money) obj;
        return amount == money.amount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount);
    }

    @Override
    public String toString() {
        return String.format("%,d원", amount);
    }

    // 천원 단위로 포맷팅
    public String toFormattedString() {
        return String.format("%,d원", amount);
    }
}