package kr.hhplus.be.server.application.usecase.reservation;

import kr.hhplus.be.server.domain.reservation.model.exception.ForbiddenQueueAccess;
import kr.hhplus.be.server.domain.reservation.model.exception.HoldNotFoundOrExpired;
import kr.hhplus.be.server.domain.reservation.model.exception.SeatAlreadyConfirmed;
import kr.hhplus.be.server.application.port.out.ConfirmedReservationPort;
import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import kr.hhplus.be.server.application.port.out.WalletPort;
import kr.hhplus.be.server.domain.reservation.service.ReservationPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional
public class ConfirmPaymentService implements ConfirmPaymentUseCase {
    private final QueuePort queue;
    private final SeatHoldPort hold;
    private final ConfirmedReservationPort confirmed;
    private final WalletPort wallet;
    private final Clock clock = Clock.systemUTC();

    @Override
    public Result confirm(Command cmd) {
        // 대기열 유효성
        if (!queue.isActive(cmd.token())) throw new ForbiddenQueueAccess();
        String userId = queue.userIdOf(cmd.token());

        // 내 홀드인지 확인
        if (!hold.isHeldBy(cmd.date(), cmd.seatNo(), userId)) throw new HoldNotFoundOrExpired();

        // 이미 확정인지 확인
        if (confirmed.exists(cmd.date(), cmd.seatNo())) throw new SeatAlreadyConfirmed();

        // 결제
        final long price = ReservationPolicy.priceOf(cmd.date(), cmd.seatNo());
        final long balance = wallet.pay(userId, price, cmd.idempotencyKey());


        // 확정 저장
        final Instant now = Instant.now(clock);
        final long reservationId;
        try {
            reservationId = confirmed.insert(cmd.date(), cmd.seatNo(), userId, price, now);
        } catch (DataIntegrityViolationException e) {
            throw new SeatAlreadyConfirmed();
        }

        // 커밋 이후에 토큰 만료
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { queue.expire(cmd.token()); }
            });
        } else {
            // 동기화가 없으면(테스트 등) 즉시 만료
            queue.expire(cmd.token());
        }

        return new Result(reservationId, balance, now);
    }
}