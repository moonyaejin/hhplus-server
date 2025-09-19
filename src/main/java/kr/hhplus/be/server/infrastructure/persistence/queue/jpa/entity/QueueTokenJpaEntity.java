package kr.hhplus.be.server.infrastructure.persistence.queue.jpa.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "queue_token",
        indexes = {
                @Index(name = "idx_token", columnList = "token", unique = true),
                @Index(name = "idx_user_id", columnList = "user_id"),
                @Index(name = "idx_status_position", columnList = "status,waiting_position"),
                @Index(name = "idx_expires_at", columnList = "expires_at")
        }
)
public class QueueTokenJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token", nullable = false, unique = true, length = 50)
    private String token;

    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TokenStatus status;

    @Column(name = "waiting_position")
    private Long waitingPosition;  // 대기 순번

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Version
    private Long version;

    protected QueueTokenJpaEntity() {}

    public QueueTokenJpaEntity(String token, String userId) {
        this.token = token;
        this.userId = userId;
        this.status = TokenStatus.WAITING;
        this.issuedAt = LocalDateTime.now();
    }

    // 활성화
    public void activate() {
        this.status = TokenStatus.ACTIVE;
        this.activatedAt = LocalDateTime.now();
        this.expiresAt = LocalDateTime.now().plusMinutes(10);
        this.waitingPosition = null;
    }

    // 만료
    public void expire() {
        this.status = TokenStatus.EXPIRED;
    }

    public boolean isExpired() {
        return status == TokenStatus.ACTIVE
                && expiresAt != null
                && LocalDateTime.now().isAfter(expiresAt);
    }

    // Getters/Setters
    public Long getId() { return id; }
    public String getToken() { return token; }
    public String getUserId() { return userId; }
    public TokenStatus getStatus() { return status; }
    public Long getWaitingPosition() { return waitingPosition; }
    public LocalDateTime getIssuedAt() { return issuedAt; }
    public LocalDateTime getActivatedAt() { return activatedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }

    public void setWaitingPosition(Long position) {
        this.waitingPosition = position;
    }

    public enum TokenStatus {
        WAITING,
        ACTIVE,
        EXPIRED,
        USED  // 예약 완료 후
    }
}