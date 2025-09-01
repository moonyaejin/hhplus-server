package kr.hhplus.be.server.reservation.application;

import kr.hhplus.be.server.reservation.domain.model.ReservationPolicy;
import kr.hhplus.be.server.reservation.port.in.HoldSeatUseCase;
import kr.hhplus.be.server.reservation.port.out.ConfirmedReservationPort;
import kr.hhplus.be.server.reservation.port.out.QueuePort;
import kr.hhplus.be.server.reservation.port.out.SeatHoldPort;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class HoldSeatService implements HoldSeatUseCase {
    private final QueuePort queue;
    private final SeatHoldPort hold;
    private final ConfirmedReservationPort confirmed;
    private final PriceCalculator price; // 또는 PricePort

    @Override
    public Result hold(Command cmd) {
        if (!queue.isActive(cmd.token())) throw new ForbiddenQueueAccess();
        String userId = queue.userIdOf(cmd.token());
        if (confirmed.exists(cmd.date(), cmd.seatNo())) throw new SeatAlreadyConfirmed();

        boolean ok = hold.tryHold(cmd.date(), cmd.seatNo(), userId, ReservationPolicy.HOLD_SECONDS);
        if (!ok) throw new SeatAlreadyHeld();

        long p = price.priceOf(cmd.date(), cmd.seatNo());
        return new Result(true, p, Instant.now().plusSeconds(ReservationPolicy.HOLD_SECONDS));
    }
}
