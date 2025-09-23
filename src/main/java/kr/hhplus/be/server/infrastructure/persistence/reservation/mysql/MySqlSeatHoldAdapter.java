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
import org.springframework.transaction.annotation.Propagation;
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

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryHold(SeatIdentifier seatIdentifier, UserId userId, Duration holdDuration) {
        Long scheduleId = seatIdentifier.scheduleId().value();
        Integer seatNumber = seatIdentifier.seatNumber().value();

        try {
            // 기존 점유 확인
            Optional<SeatHoldJpaEntity> existing = repository
                    .findByScheduleIdAndSeatNumber(scheduleId, seatNumber);

            if (existing.isPresent()) {
                SeatHoldJpaEntity existingHold = existing.get();

                // 만료되지 않았으면 실패
                if (!existingHold.isExpired()) {
                    log.debug("좌석이 이미 점유 중: scheduleId={}, seatNo={}", scheduleId, seatNumber);
                    return false;
                }

                // 만료된 경우 ID로 삭제
                repository.deleteById(existingHold.getId());
                repository.flush(); // 즉시 반영
            }

            // 새로운 점유 생성
            LocalDateTime now = LocalDateTime.now();
            SeatHoldJpaEntity newHold = new SeatHoldJpaEntity(
                    scheduleId,
                    seatNumber,
                    userId.asString(),
                    now,
                    now.plus(holdDuration)
            );

            repository.save(newHold);
            repository.flush(); // 즉시 반영

            log.info("좌석 점유 성공: scheduleId={}, seatNo={}, userId={}",
                    scheduleId, seatNumber, userId.asString());
            return true;

        } catch (DataIntegrityViolationException e) {
            // 동시성 이슈로 중복 발생
            log.debug("좌석 점유 실패 - 동시 요청: scheduleId={}, seatNo={}", scheduleId, seatNumber);
            return false;
        } catch (Exception e) {
            log.error("좌석 점유 중 예외 발생: scheduleId={}, seatNo={}", scheduleId, seatNumber, e);
            throw new RuntimeException("좌석 점유 실패", e); // 예외를 던지지 말고 false 반환
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

    // 만료된 점유 정리
    private void cleanupExpiredHolds() {
        try {
            int deleted = repository.deleteExpiredHolds(LocalDateTime.now());
            if (deleted > 0) {
                log.debug("만료된 좌석 점유 {}건 정리", deleted);
            }
        } catch (Exception e) {
            log.error("만료된 홀드 정리 실패", e);
            // 실패해도 계속 진행
        }
    }
}