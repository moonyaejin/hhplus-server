package kr.hhplus.be.server.domain.payment;

import kr.hhplus.be.server.domain.common.UserId;

import java.time.LocalDateTime;

public class Wallet {
    private final WalletId id;
    private final UserId userId;
    private Balance balance;
    private final LocalDateTime createdAt;
    private long version;

    private Wallet(WalletId id, UserId userId, Balance balance, LocalDateTime createdAt) {
        this.id = id;
        this.userId = userId;
        this.balance = balance;
        this.createdAt = createdAt;
        this.version = 0;
    }

    public static Wallet create(UserId userId, LocalDateTime createdAt) {
        WalletId id = new WalletId(userId.value() + "_wallet");
        return new Wallet(id, userId, new Balance(0), createdAt);
    }

    public static Wallet restore(WalletId id, UserId userId, Balance balance, LocalDateTime createdAt, long version) {
        Wallet wallet = new Wallet(id, userId, balance, createdAt);
        wallet.version = version;
        return wallet;
    }

    public void charge(long amount, String idempotencyKey) {
        this.balance = this.balance.add(amount);
    }

    public void pay(long amount, String idempotencyKey) {
        this.balance = this.balance.subtract(amount);
    }

    // Getters
    public WalletId getId() { return id; }
    public UserId getUserId() { return userId; }
    public Balance getBalance() { return balance; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}