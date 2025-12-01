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

    @Column(name = "payment_requested_at")
    private LocalDateTime paymentRequestedAt;

    @Column(name = "payment_fail_reason")
    private String paymentFailReason;

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
        if (version != null && version > 0) {
            this.version = version;
        }
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public ReservationJpaEntity(String id, String userId, Long concertScheduleId,
                                Integer seatNumber, Long price, ReservationStatus status,
                                LocalDateTime temporaryAssignedAt, LocalDateTime confirmedAt,
                                LocalDateTime paymentRequestedAt, String paymentFailReason,
                                Long version) {
        this(id, userId, concertScheduleId, seatNumber, price, status,
                temporaryAssignedAt, confirmedAt, version);
        this.paymentRequestedAt = paymentRequestedAt;
        this.paymentFailReason = paymentFailReason;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters
    public String getId() { return id; }
    public String getUserId() { return userId; }
    public Long getConcertScheduleId() { return concertScheduleId; }
    public Integer getSeatNumber() { return seatNumber; }
    public Long getPrice() { return price; }
    public ReservationStatus getStatus() { return status; }
    public LocalDateTime getTemporaryAssignedAt() { return temporaryAssignedAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public LocalDateTime getPaymentRequestedAt() { return paymentRequestedAt; }
    public String getPaymentFailReason() { return paymentFailReason; }
    public Long getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    // Setters
    public void setStatus(ReservationStatus status) { this.status = status; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public void setPaymentRequestedAt(LocalDateTime paymentRequestedAt) { this.paymentRequestedAt = paymentRequestedAt; }
    public void setPaymentFailReason(String paymentFailReason) { this.paymentFailReason = paymentFailReason; }
}