package kr.hhplus.be.server.application.usecase.payment;

import java.util.Objects;

public final class Money {
    private final long value;

    public static Money of(long value) {
        if (value < 0) throw new IllegalArgumentException("money must be >= 0");
        return new Money(value);
    }

    private Money(long value) { this.value = value; }

    public long value() { return value; }

    public Money plus(long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount must be >= 0");
        return new Money(this.value + amount);
    }

    public Money minus(long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount must be >= 0");
        long next = this.value - amount;
        if (next < 0) throw new IllegalArgumentException("insufficient balance");
        return new Money(next);
    }

    @Override public boolean equals(Object o) {
        return (this == o) || (o instanceof Money m && value == m.value);
    }
    @Override public int hashCode() { return Objects.hash(value); }
    @Override public String toString() { return "₩" + value; }
}
