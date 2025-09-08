package kr.hhplus.be.server.domain.reservation.model;

import java.time.LocalDate;
import java.util.Objects;

public record SeatId(LocalDate date, int seatNo) {

    // compact constructor (필드 검증만 가능)
    public SeatId {
        if (date == null) throw new IllegalArgumentException("date cannot be null");
        if (seatNo <= 0) throw new IllegalArgumentException("seatNo must be greater than zero");
    }

    // equals / hashCode / toString은 record가 자동 생성
    @Override
    public boolean equals(Object o) {
        return (o instanceof SeatId s) &&
                this.seatNo == s.seatNo &&
                this.date.equals(s.date);
    }

    @Override
    public int hashCode() {
        return Objects.hash(date, seatNo);
    }

    @Override
    public String toString() {
        return "%s#%d".formatted(date, seatNo);
    }
}
