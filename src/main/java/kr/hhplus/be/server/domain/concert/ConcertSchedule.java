package kr.hhplus.be.server.domain.concert;

import java.time.LocalDate;

public class ConcertSchedule {
    private final ConcertScheduleId id;
    private final ConcertId concertId;
    private final LocalDate concertDate;
    private final int totalSeats;

    public ConcertSchedule(ConcertScheduleId id, ConcertId concertId, LocalDate concertDate, int totalSeats) {
        if (concertDate == null) {
            throw new IllegalArgumentException("콘서트 날짜는 필수입니다");
        }
        if (totalSeats <= 0 || totalSeats > 50) {
            throw new IllegalArgumentException("좌석 수는 1~50 사이여야 합니다");
        }

        this.id = id;
        this.concertId = concertId;
        this.concertDate = concertDate;
        this.totalSeats = totalSeats;
    }

    public ConcertScheduleId getId() { return id; }
    public ConcertId getConcertId() { return concertId; }
    public LocalDate getConcertDate() { return concertDate; }
    public int getTotalSeats() { return totalSeats; }
}