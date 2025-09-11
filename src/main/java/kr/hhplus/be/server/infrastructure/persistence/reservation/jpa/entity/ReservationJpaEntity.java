package kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.entity;

import jakarta.persistence.*;
import kr.hhplus.be.server.domain.reservation.ReservationStatus;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "reservation",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_reservation_seat_temp",
                        columnNames = {"concert_schedule_id", "seat_number", "status"}
                )
        }
)
public class ReservationJpaEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "concert_schedule_id", nullable = false)
    private Long concertScheduleId;

    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber;

    @Column(name = "price", nullable = false)
    private Long price;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReservationStatus status;

    @Column(name = "temporary_assigned_at", nullable = false)
    private LocalDateTime temporaryAssignedAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ReservationJpaEntity() {}

    public ReservationJpaEntity(String id, String userId, Long concertScheduleId,
                                Integer seatNumber, Long price, ReservationStatus status,
                                LocalDateTime temporaryAssignedAt, LocalDateTime confirmedAt, Long version) {
        this.id = id;
        this.userId = userId;
        this.concertScheduleId = concertScheduleId;
        this.seatNumber = seatNumber;
        this.price = price;
        this.status = status;
        this.temporaryAssignedAt = temporaryAssignedAt;
        this.confirmedAt = confirmedAt;
        this.version = version;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public String getId() { return id; }
    public String getUserId() { return userId; }
    public Long getConcertScheduleId() { return concertScheduleId; }
    public Integer getSeatNumber() { return seatNumber; }
    public Long getPrice() { return price; }
    public ReservationStatus getStatus() { return status; }
    public LocalDateTime getTemporaryAssignedAt() { return temporaryAssignedAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public Long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setStatus(ReservationStatus status) { this.status = status; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
}