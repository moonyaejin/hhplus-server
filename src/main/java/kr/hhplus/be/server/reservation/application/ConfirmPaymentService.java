package kr.hhplus.be.server.reservation.application;

import jakarta.transaction.Transactional;
import kr.hhplus.be.server.reservation.domain.exception.ForbiddenQueueAccess;
import kr.hhplus.be.server.reservation.domain.exception.HoldNotFoundOrExpired;
import kr.hhplus.be.server.reservation.domain.exception.SeatAlreadyConfirmed;
import kr.hhplus.be.server.reservation.port.in.ConfirmPaymentUseCase;
import kr.hhplus.be.server.reservation.port.out.ConfirmedReservationPort;
import kr.hhplus.be.server.reservation.port.out.QueuePort;
import kr.hhplus.be.server.reservation.port.out.SeatHoldPort;
import kr.hhplus.be.server.reservation.port.out.WalletPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ConfirmPaymentService implements ConfirmPaymentUseCase {
    private final QueuePort queue;
    private final SeatHoldPort hold;
    private final ConfirmedReservationPort confirmed;
    private final WalletPort wallet;

    private static final long FIXED_PRICE = 80000L;

    @Transactional
    @Override
    public Result confirm(Command cmd) {
        if (!queue.isActive(cmd.token())) throw new ForbiddenQueueAccess();
        String userId = queue.userIdOf(cmd.token());

        if (!hold.isHeldBy(cmd.date(), cmd.seatNo(), userId)) throw new HoldNotFoundOrExpired();
        if (confirmed.exists(cmd.date(), cmd.seatNo())) throw new SeatAlreadyConfirmed();

        long balance = wallet.pay(userId, FIXED_PRICE, cmd.idempotencyKey());

        long id = confirmed.insert(cmd.date(), cmd.seatNo(), userId, FIXED_PRICE, Instant.now());
        queue.expire(cmd.token());

        return new Result(id, balance, Instant.now());
    }
}

