package kr.hhplus.be.server.application.usecase.payment;

import kr.hhplus.be.server.domain.reservation.model.exception.InsufficientBalance;

import java.util.Objects;
import java.util.UUID;

public class Wallet {

    private final UUID userId;
    private long balance; // 간단히 long 보유. 필요 시 Money로 치환 가능

    public Wallet(UUID userId, long balance) {
        if (userId == null) throw new IllegalArgumentException("userId");
        if (balance < 0) throw new IllegalArgumentException("must not be negative");
        this.userId = userId;
        this.balance = balance;
    }

    public UUID userId() { return userId; }
    public long balance() { return balance; }

    // 충전
    public void charge(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be greater than zero");
        this.balance += amount;
    }

    // 결제
    public void pay(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be greater than zero");
        long next = this.balance - amount;
        if (next < 0) throw new InsufficientBalance();
        this.balance = next;
    }

    @Override public String toString() {
        return "Wallet{userId=" + userId + ", balance=" + balance + '}';
    }

    @Override public boolean equals(Object o) {
        return (this == o) || (o instanceof Wallet w && userId.equals(w.userId));
    }
    @Override public int hashCode() { return Objects.hash(userId); }
}
