package kr.hhplus.be.server.reservation.application;

import jakarta.transaction.Transactional;
import kr.hhplus.be.server.reservation.port.in.ConfirmPaymentUseCase;
import kr.hhplus.be.server.reservation.port.out.ConfirmedReservationPort;
import kr.hhplus.be.server.reservation.port.out.QueuePort;
import kr.hhplus.be.server.reservation.port.out.SeatHoldPort;
import kr.hhplus.be.server.reservation.port.out.WalletPort;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ConfirmPaymentService implements ConfirmPaymentUseCase {
    private final QueuePort queue;
    private final SeatHoldPort hold;
    private final ConfirmedReservationPort confirmed;
    private final WalletPort wallet;
    private final PriceCalculator price;

    @Transactional
    @Override
    public Result confirm(Command cmd) {
        if (!queue.isActive(cmd.token())) throw new ForbiddenQueueAccess();
        String userId = queue.userIdOf(cmd.token());

        if (!hold.isHeldBy(cmd.date(), cmd.seatNo(), userId)) throw new HoldNotFoundOrExpired();
        if (confirmed.exists(cmd.date(), cmd.seatNo())) throw new SeatAlreadyConfirmed();

        long amount = price.priceOf(cmd.date(), cmd.seatNo());
        long balance = wallet.pay(userId, amount, cmd.idempotencyKey());

        long id = confirmed.insert(cmd.date(), cmd.seatNo(), userId, amount, Instant.now());
        queue.expire(cmd.token());
        // hold.release(cmd.date(), cmd.seatNo()); // 옵션 (TTL 만료에 맡겨도 됨)

        return new Result(id, balance, Instant.now());
    }
}

