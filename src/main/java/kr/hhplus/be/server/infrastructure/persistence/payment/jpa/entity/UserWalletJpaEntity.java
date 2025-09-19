package kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_wallet")
public class UserWalletJpaEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", columnDefinition = "BINARY(16)")
    private UUID userId;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Version
    private long version;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserWalletJpaEntity() {}

    public UserWalletJpaEntity(UUID userId, long initialBalance) {
        this.userId = userId;
        this.balance = initialBalance;
        this.updatedAt = Instant.now();
    }

    public UUID getUserId() { return userId; }
    public long getBalance() { return balance; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setBalance(long balance) {
        this.balance = balance;
    }
}
