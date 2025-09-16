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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
@Primary  // Redis 구현체보다 우선
@RequiredArgsConstructor
public class MySqlSeatHoldAdapter implements SeatHoldPort {

    private final SeatHoldJpaRepository repository;

    @Override
    @Transactional
    public boolean tryHold(SeatIdentifier seatIdentifier, UserId userId, Duration holdDuration) {
        Long scheduleId = seatIdentifier.scheduleId().value();
        Integer seatNumber = seatIdentifier.seatNumber().value();

        // 1. 먼저 만료된 점유 정리
        cleanupExpiredHolds();

        // 2. 이미 점유되어 있는지 확인
        Optional<SeatHoldJpaEntity> existing = repository
                .findByScheduleIdAndSeatNumber(scheduleId, seatNumber);

        if (existing.isPresent() && !existing.get().isExpired()) {
            return false;  // 이미 다른 사용자가 점유 중
        }

        // 3. 만료된 점유가 있다면 삭제
        if (existing.isPresent()) {
            repository.delete(existing.get());
        }

        // 4. 새로운 점유 시도
        try {
            LocalDateTime now = LocalDateTime.now();
            SeatHoldJpaEntity newHold = new SeatHoldJpaEntity(
                    scheduleId,
                    seatNumber,
                    userId.asString(),
                    now,
                    now.plus(holdDuration)
            );
            repository.save(newHold);
            return true;
        } catch (DataIntegrityViolationException e) {
            // 동시성 이슈로 실패
            log.debug("좌석 점유 실패 - 동시 요청: {}", seatIdentifier);
            return false;
        }
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

        // 스케줄별로 그룹핑해서 조회 (더 효율적)
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
                if (!hold.isExpired()) {
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

    // 만료된 점유 정리 (주기적으로 실행 또는 조회 시 실행)
    private void cleanupExpiredHolds() {
        int deleted = repository.deleteExpiredHolds(LocalDateTime.now());
        if (deleted > 0) {
            log.debug("만료된 좌석 점유 {}건 정리", deleted);
        }
    }
}