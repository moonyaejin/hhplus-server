package kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_wallet")
public class UserWallet {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", columnDefinition = "BINARY(16)")
    private UUID userId;

    @Column(name = "balance", nullable = false)
    private Long balance;

    @Version
    private long version;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false, updatable = true)
    private Instant updatedAt;

    protected UserWallet() {}

    public UserWallet(final UUID userId, long initialBalance) {
        if (userId == null) throw new IllegalArgumentException("userId cannot be null");
        if (initialBalance < 0) throw new IllegalArgumentException("initialBalance cannot be negative");
        this.userId = userId;
        this.balance = initialBalance;
    }

    public UUID getUserId() { return userId; }
    public Long getBalance() { return balance; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    // 충전
    public void increase(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be greater than zero");
        this.balance += amount;
    }

    // 결제 차감
    public void decrease(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be greater than zero");
        long next = this.balance - amount;
        if (next < 0) throw new IllegalArgumentException("insufficient balance");
        this.balance = next;
    }
}
