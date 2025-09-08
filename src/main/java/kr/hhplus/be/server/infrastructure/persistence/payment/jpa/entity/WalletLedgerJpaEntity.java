package kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallet_ledger",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_wallet_idem",
                columnNames = {"user_id", "idempotency_key"}
        ))
public class WalletLedgerJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID userId;

    @Column(name = "amount", nullable = false)
    private long amount; // 충전:+, 결제:-, 환불:+

    @Column(name = "reason", nullable = false, length = 32)
    private String reason; // CHARGE / PAYMENT / REFUND

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WalletLedgerJpaEntity() {}

    public WalletLedgerJpaEntity(UUID userId, long amount, String reason, String idempotencyKey) {
        this.userId = userId;
        this.amount = amount;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
    public long getAmount() { return amount; }
    public String getReason() { return reason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}
