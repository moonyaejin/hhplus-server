package kr.hhplus.be.server.reservation.application;

import kr.hhplus.be.server.reservation.domain.exception.ForbiddenQueueAccess;
import kr.hhplus.be.server.reservation.domain.exception.SeatAlreadyConfirmed;
import kr.hhplus.be.server.reservation.domain.exception.SeatAlreadyHeld;
import kr.hhplus.be.server.reservation.domain.model.ReservationPolicy;
import kr.hhplus.be.server.reservation.port.in.HoldSeatUseCase;
import kr.hhplus.be.server.reservation.port.out.ConfirmedReservationPort;
import kr.hhplus.be.server.reservation.port.out.QueuePort;
import kr.hhplus.be.server.reservation.port.out.SeatHoldPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class HoldSeatService implements HoldSeatUseCase {
    private final QueuePort queue;
    private final SeatHoldPort hold;
    private final ConfirmedReservationPort confirmed;

    // 일단 가격 80,000 고정
    private static final long FIXED_PRICE = 80000L;

    @Override
    public Result hold(Command cmd) {
        if (!queue.isActive(cmd.token())) throw new ForbiddenQueueAccess();
        var userId = queue.userIdOf(cmd.token());
        if (confirmed.exists(cmd.date(), cmd.seatNo())) throw new SeatAlreadyConfirmed();

        boolean ok = hold.tryHold(cmd.date(), cmd.seatNo(), userId, ReservationPolicy.HOLD_SECONDS);
        if (!ok) throw new SeatAlreadyHeld();

        return new Result(true, FIXED_PRICE, Instant.now().plusSeconds(ReservationPolicy.HOLD_SECONDS));
    }
}
