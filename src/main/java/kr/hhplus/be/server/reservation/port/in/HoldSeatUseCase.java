package kr.hhplus.be.server.reservation.port.in;

import java.time.Instant;
import java.time.LocalDate;

public interface HoldSeatUseCase {
    record Command(String token, LocalDate date, int seatNo) {}
    record Result(boolean success, long price, Instant holdExpiresAt) {}
    Result hold(Command cmd);
}
