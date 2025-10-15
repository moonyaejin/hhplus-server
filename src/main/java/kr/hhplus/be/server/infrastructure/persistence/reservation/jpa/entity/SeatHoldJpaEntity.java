package kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "seat_hold",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_seat_hold",
                columnNames = {"schedule_id", "seat_number"}
        ),
        indexes = {
                @Index(name = "idx_expires_at", columnList = "expires_at"),
                @Index(name = "idx_user_id", columnList = "user_id")
        }
)
public class SeatHoldJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber;

    @Column(name = "user_id", nullable = false)
    private String userId;  // UUID as String

    @Column(name = "held_at", nullable = false)
    private LocalDateTime heldAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Version
    private Long version;  // 낙관적 잠금용

    // 생성자
    protected SeatHoldJpaEntity() {}

    public SeatHoldJpaEntity(Long scheduleId, Integer seatNumber,
                             String userId, LocalDateTime heldAt,
                             LocalDateTime expiresAt) {
        this.scheduleId = scheduleId;
        this.seatNumber = seatNumber;
        this.userId = userId;
        this.heldAt = heldAt;
        this.expiresAt = expiresAt;
    }

    // 낙관적 락을 위한 업데이트 메서드 추가
    public void updateHold(String newUserId, LocalDateTime newHeldAt, LocalDateTime newExpiresAt) {
        this.userId = newUserId;
        this.heldAt = newHeldAt;
        this.expiresAt = newExpiresAt;
        // version은 JPA가 자동으로 증가시킴
    }

    // Getter들
    public Long getId() { return id; }
    public Long getScheduleId() { return scheduleId; }
    public Integer getSeatNumber() { return seatNumber; }
    public String getUserId() { return userId; }
    public LocalDateTime getHeldAt() { return heldAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public Long getVersion() { return version; }  // version getter 추가

    // 만료 체크 메서드 개선
    public boolean isExpired() {
        return isExpired(LocalDateTime.now());
    }

    public boolean isExpired(LocalDateTime at) {
        return at.isAfter(expiresAt);
    }
}