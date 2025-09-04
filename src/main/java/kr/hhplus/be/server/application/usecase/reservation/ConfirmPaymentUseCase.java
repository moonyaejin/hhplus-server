package kr.hhplus.be.server.application.usecase.reservation;

import java.time.Instant;
import java.time.LocalDate;

public interface ConfirmPaymentUseCase {
    record Command(String token, LocalDate date, int seatNo, String idempotencyKey) {}
    record Result(long reservationId, long balance, Instant paidAt) {}
    Result confirm(Command cmd);
}
