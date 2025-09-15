package kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity;

import jakarta.persistence.*;
        import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "concert_schedule",
        uniqueConstraints = @UniqueConstraint(name = "uq_concert_date", columnNames = {"concert_id","concert_date"})
)
public class ConcertScheduleJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "concert_id", nullable = false)
    private ConcertJpaEntity concert;

    @Column(name = "concert_date", nullable = false)
    private LocalDate concertDate;

    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected ConcertScheduleJpaEntity() {}
    public ConcertScheduleJpaEntity(ConcertJpaEntity concert, LocalDate date, int seatCount) {
        this.concert = concert;
        this.concertDate = date;
        this.seatCount = seatCount;
    }

    public Long getId() { return id; }
    public ConcertJpaEntity getConcert() { return concert; }
    public LocalDate getConcertDate() { return concertDate; }
    public int getSeatCount() { return seatCount; }
}
