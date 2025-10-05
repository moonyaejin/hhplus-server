package kr.hhplus.be.server.infrastructure.persistence.reservation.mysql;

import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.reservation.SeatHoldStatus;
import kr.hhplus.be.server.domain.reservation.SeatIdentifier;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.entity.SeatHoldJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.SeatHoldJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class MySqlSeatHoldAdapter implements SeatHoldPort {

    private final SeatHoldJpaRepository repository;
    private final SeatHoldTransactionService transactionService;  // 추가

    @Override
    public boolean tryHold(SeatIdentifier seatIdentifier, UserId userId, Duration holdDuration) {
        Long scheduleId = seatIdentifier.scheduleId().value();
        Integer seatNumber = seatIdentifier.seatNumber().value();
        LocalDateTime now = LocalDateTime.now();

        // 트랜잭션 서비스로 위임
        return transactionService.tryHold(
                scheduleId,
                seatNumber,
                userId.asString(),
                now,
                now.plus(holdDuration)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isHeldBy(SeatIdentifier seatIdentifier, UserId userId) {
        Optional<SeatHoldJpaEntity> hold = repository.findByScheduleIdAndSeatNumber(
                seatIdentifier.scheduleId().value(),
                seatIdentifier.seatNumber().value()
        );

        return hold.isPresent()
                && !hold.get().isExpired()
                && hold.get().getUserId().equals(userId.asString());
    }

    @Override
    @Transactional
    public void release(SeatIdentifier seatIdentifier) {
        repository.deleteByScheduleIdAndSeatNumber(
                seatIdentifier.scheduleId().value(),
                seatIdentifier.seatNumber().value()
        );

        log.debug("좌석 점유 해제: scheduleId={}, seatNo={}",
                seatIdentifier.scheduleId().value(),
                seatIdentifier.seatNumber().value());
    }

    @Override
    @Transactional(readOnly = true)
    public SeatHoldStatus getHoldStatus(SeatIdentifier seatIdentifier) {
        Optional<SeatHoldJpaEntity> hold = repository.findByScheduleIdAndSeatNumber(
                seatIdentifier.scheduleId().value(),
                seatIdentifier.seatNumber().value()
        );

        if (hold.isEmpty() || hold.get().isExpired()) {
            return null;
        }

        SeatHoldJpaEntity entity = hold.get();
        return new SeatHoldStatus(
                UserId.ofString(entity.getUserId()),
                entity.getHeldAt(),
                entity.getExpiresAt()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Map<SeatIdentifier, SeatHoldStatus> getHoldStatusBulk(List<SeatIdentifier> seatIdentifiers) {
        Map<SeatIdentifier, SeatHoldStatus> resultMap = new HashMap<>();

        if (seatIdentifiers == null || seatIdentifiers.isEmpty()) {
            return resultMap;
        }

        Map<Long, List<Integer>> scheduleToSeats = new HashMap<>();
        for (SeatIdentifier seat : seatIdentifiers) {
            scheduleToSeats
                    .computeIfAbsent(seat.scheduleId().value(), k -> new ArrayList<>())
                    .add(seat.seatNumber().value());
        }

        LocalDateTime now = LocalDateTime.now();

        for (Map.Entry<Long, List<Integer>> entry : scheduleToSeats.entrySet()) {
            List<SeatHoldJpaEntity> holds = repository.findByScheduleIdAndSeatNumberIn(
                    entry.getKey(),
                    entry.getValue()
            );

            for (SeatHoldJpaEntity hold : holds) {
                if (!hold.isExpired(now)) {
                    SeatIdentifier seatId = SeatIdentifier.of(
                            hold.getScheduleId(),
                            hold.getSeatNumber()
                    );

                    SeatHoldStatus status = new SeatHoldStatus(
                            UserId.ofString(hold.getUserId()),
                            hold.getHeldAt(),
                            hold.getExpiresAt()
                    );

                    resultMap.put(seatId, status);
                }
            }
        }

        return resultMap;
    }

    @Transactional
    public void cleanupExpiredHolds() {
        try {
            int deleted = repository.deleteExpiredHolds(LocalDateTime.now());
            if (deleted > 0) {
                log.info("만료된 좌석 점유 {}건 정리 완료", deleted);
            }
        } catch (Exception e) {
            log.error("만료된 홀드 정리 실패", e);
        }
    }
}