package kr.hhplus.be.server.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_wallet")
public class UserWallet {

    @Id
    @Column(name = "user_id", columnDefinition = "BINARY(16)")
    private UUID userId;

    @Column(name = "balance", nullable = false)
    private Long balance;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
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

    // 충전
    public void increase(long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount cannot be negative");
        this.balance += amount;
    }

    // 결제 차감
    public void decrease(long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount cannot be negative");
        long next = this.balance - amount;
        if  (next < 0) throw new IllegalArgumentException("insufficient balance");
        this.balance = next;
    }
}
