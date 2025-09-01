package kr.hhplus.be.server.reservation.adapter.web;

import kr.hhplus.be.server.reservation.port.in.ConfirmPaymentUseCase;
import kr.hhplus.be.server.reservation.port.in.HoldSeatUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/reservations")
@RequiredArgsConstructor
public class ReservationController {
    private final HoldSeatUseCase holdUC;
    private final ConfirmPaymentUseCase confirmUC;

    @PostMapping("/hold")
    public HoldResponse hold(@RequestHeader("X-Queue-Token") String token, @RequestBody HoldRequest req){
        var r = holdUC.hold(new HoldSeatUseCase.Command(token, req.date(), req.seatNo()));
        return new HoldResponse(r.price(), r.holdExpiresAt());
    }
    @PostMapping("/confirm")
    public ConfirmResponse confirm(@RequestHeader("X-Queue-Token") String token,
                                   @RequestHeader("Idempotency-Key") String idem,
                                   @RequestBody ConfirmRequest req){
        var r = confirmUC.confirm(new ConfirmPaymentUseCase.Command(token, req.date(), req.seatNo(), idem));
        return new ConfirmResponse(r.reservationId(), r.balance(), r.paidAt());
    }

    public record HoldRequest(LocalDate date, int seatNo){}
    public record HoldResponse(long price, Instant holdExpiresAt){}
    public record ConfirmRequest(LocalDate date, int seatNo){}
    public record ConfirmResponse(long reservationId, long balance, Instant paidAt){}
}
