package kr.hhplus.be.server.reservation.adapter.persistence;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "confirmed_reservation")
public class ConfirmedReservationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "user_id", columnDefinition = "BINARY(16)", nullable = false)
    private UUID userId;

    @Column(name = "concert_date", nullable = false)
    private LocalDate concertDate;

    @Column(name = "seat_no", nullable = false)
    private Integer seatNo;

    @Column(name = "price", nullable = false)
    private Long price;

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConfirmedReservationJpaEntity() {}

    protected ConfirmedReservationJpaEntity(UUID userId, LocalDate concertDate, Integer seatNo, Long price, Instant paidAt) {
        this.userId = userId;
        this.concertDate = concertDate;
        this.seatNo = seatNo;
        this.price = price;
        this.paidAt = paidAt;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public long getId() {
        return id;
    }
}
