package kr.hhplus.be.server.reservation.port.out;

import java.time.Instant;
import java.time.LocalDate;

public interface ConfirmedReservationPort {
    boolean exists(LocalDate date, int seatNo);
    long insert(LocalDate date, int seatNo, String userId, long price, Instant paidAt);
}
