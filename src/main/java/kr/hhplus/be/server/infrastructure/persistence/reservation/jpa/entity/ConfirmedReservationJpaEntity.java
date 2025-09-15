package kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "confirmed_reservation",
        uniqueConstraints = @UniqueConstraint(name="uq_confirmed_unique", columnNames={"concert_date","seat_no"})
)
public class ConfirmedReservationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name="user_id", columnDefinition="BINARY(16)", nullable=false)
    private UUID userId;

    @Column(name="concert_date", nullable=false)
    private LocalDate concertDate;

    @Column(name="seat_no", nullable=false)
    private int seatNo;

    @Column(name="price", nullable=false)
    private long price;

    @Column(name="paid_at", nullable=false)
    private Instant paidAt;

    @Column(name="created_at", nullable=false, updatable=false, insertable=false)
    private Instant createdAt;

    @Column(name="updated_at", nullable=false, updatable=false, insertable=false)
    private Instant updatedAt;

    protected ConfirmedReservationJpaEntity() {}

    public ConfirmedReservationJpaEntity(UUID userId, LocalDate concertDate, int seatNo, long price, Instant paidAt) {
        this.userId = userId;
        this.concertDate = concertDate;
        this.seatNo = seatNo;
        this.price = price;
        this.paidAt = paidAt;
    }

    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
    public LocalDate getConcertDate() { return concertDate; }
    public int getSeatNo() { return seatNo; }
    public long getPrice() { return price; }
    public Instant getPaidAt() { return paidAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
