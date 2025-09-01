package kr.hhplus.be.server.reservation.domain.model;

import java.time.LocalDate;

public record SeatId(LocalDate date, int no) {
    public SeatId {
        if (no < 1 || no > 50) throw new IllegalArgumentException("invalid seat");
    }
}
