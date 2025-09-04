package kr.hhplus.be.server.application.usecase.reservation;

import kr.hhplus.be.server.domain.reservation.model.exception.ForbiddenQueueAccess;
import kr.hhplus.be.server.domain.reservation.model.exception.SeatAlreadyConfirmed;
import kr.hhplus.be.server.domain.reservation.model.exception.SeatAlreadyHeld;
import kr.hhplus.be.server.domain.reservation.service.ReservationPolicy;
import kr.hhplus.be.server.application.port.out.ConfirmedReservationPort;
import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional
public class HoldSeatService implements HoldSeatUseCase {
    private final QueuePort queue;
    private final SeatHoldPort hold;
    private final ConfirmedReservationPort confirmed;

    private final Clock clock = Clock.systemUTC();

    @Override
    public Result hold(Command cmd) {
        // 토큰 유효성
        if (!queue.isActive(cmd.token())) throw new ForbiddenQueueAccess();
        final String userId = queue.userIdOf(cmd.token());
        if (userId == null || userId.isBlank()) throw new ForbiddenQueueAccess();

        // 이미 확정 좌석이면 실패
        if (confirmed.exists(cmd.date(), cmd.seatNo())) throw new SeatAlreadyConfirmed();

        // 홀드 시도
        final boolean ok = hold.tryHold(cmd.date(), cmd.seatNo(), userId, ReservationPolicy.HOLD_SECONDS);
        if (!ok) throw new SeatAlreadyHeld();

        // 가격은 정책에서 계산
        final long price = ReservationPolicy.priceOf(cmd.date(), cmd.seatNo());

        // 만료 시각은 now + TTL
        final Instant now = Instant.now(clock);
        final Instant expiresAt = now.plusSeconds(ReservationPolicy.HOLD_SECONDS);

        return new Result(true, price, expiresAt);
    }
}