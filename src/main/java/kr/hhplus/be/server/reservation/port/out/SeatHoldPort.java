package kr.hhplus.be.server.reservation.port.out;

import java.time.LocalDate;

public interface SeatHoldPort {
    boolean tryHold(LocalDate date, int seatNo, String userId, int ttlSec);
    boolean isHeldBy(LocalDate date, int seatNo, String userId);
    void release(LocalDate date, int seatNo);
}
