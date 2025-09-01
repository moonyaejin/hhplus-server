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

    public UserWallet(final UUID userId, Long initialBalance) {
        this.userId = userId;
        this.balance = initialBalance;
    }

    public UUID getUserId() { return userId; }
    public Long getBalance() { return balance; }
    public Instant getUpdatedAt() { return updatedAt; }
}
