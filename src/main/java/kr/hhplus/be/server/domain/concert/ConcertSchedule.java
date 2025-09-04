package kr.hhplus.be.server.domain.concert;

import java.time.LocalDate;

public class ConcertSchedule {
    private final Long id;
    private final Long concertId;
    private final LocalDate concertDate;
    private final int seatCount;

    public ConcertSchedule(Long id, Long concertId, LocalDate concertDate, int seatCount) {
        if (concertDate == null) throw new IllegalArgumentException("concertDate cannot be null");
        if (seatCount <= 0) throw new IllegalArgumentException("seatCount must be positive");

        this.id = id;
        this.concertId = concertId;
        this.concertDate = concertDate;
        this.seatCount = seatCount;
    }

    public Long getId() { return id; }
    public Long getConcertId() { return concertId; }
    public LocalDate getConcertDate() { return concertDate; }
    public int getSeatCount() { return seatCount; }
}
