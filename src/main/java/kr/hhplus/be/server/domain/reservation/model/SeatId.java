package kr.hhplus.be.server.domain.reservation.model;

import java.time.LocalDate;
import java.util.Objects;

public record SeatId {
        private final LocalDate date;
        private final int seatNo;

    public SeatId(LocalDate date, int seatNo) {
        if (date == null) throw new IllegalArgumentException("date cannot be null");
        if (seatNo <= 0) throw new IllegalArgumentException("seatNo must be positive");
        this.date = date;
        this.seatNo = seatNo;
    }

    public LocalDate getDate() { return date; }
    public int seatNo() { return seatNo; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SeatId)) return false;
        return seatNo == s.seatNo && date.equals(s.date);
    }

    @Override public int hashCode() { return Objects.hash(date, seatNo); }
    @Override public String toString() { return "%s#%d".formatted(date, seatNo); }
}
