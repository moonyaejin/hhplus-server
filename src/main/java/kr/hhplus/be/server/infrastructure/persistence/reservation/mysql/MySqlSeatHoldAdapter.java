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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
    private static final int MAX_RETRY = 3;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryHold(SeatIdentifier seatIdentifier, UserId userId, Duration holdDuration) {
        // 낙관적 락 재시도 로직 추가
        int retryCount = 0;

        while (retryCount < MAX_RETRY) {
            try {
                return attemptHold(seatIdentifier, userId, holdDuration);
            } catch (ObjectOptimisticLockingFailureException e) {
                // 낙관적 락 충돌 시 재시도
                retryCount++;
                log.warn("낙관적 락 충돌, 재시도 {}/{}: scheduleId={}, seatNo={}",
                        retryCount, MAX_RETRY,
                        seatIdentifier.scheduleId().value(),
                        seatIdentifier.seatNumber().value());

                if (retryCount >= MAX_RETRY) {
                    log.error("최대 재시도 횟수 초과");
                    return false;
                }

                // 짧은 대기 후 재시도 (exponential backoff)
                try {
                    Thread.sleep(50 * retryCount);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } catch (Exception e) {
                log.error("좌석 점유 중 예외 발생: scheduleId={}, seatNo={}",
                        seatIdentifier.scheduleId().value(),
                        seatIdentifier.seatNumber().value(), e);
                return false;
            }
        }

        return false;
    }

    private boolean attemptHold(SeatIdentifier seatIdentifier, UserId userId, Duration holdDuration) {
        Long scheduleId = seatIdentifier.scheduleId().value();
        Integer seatNumber = seatIdentifier.seatNumber().value();
        LocalDateTime now = LocalDateTime.now();

        try {
            // 비관적 락으로 변경 - 이 부분만 수정!
            Optional<SeatHoldJpaEntity> existing = repository
                    .findByScheduleIdAndSeatNumberWithLock(scheduleId, seatNumber);

            if (existing.isPresent()) {
                SeatHoldJpaEntity existingHold = existing.get();

                // 만료되지 않았으면 실패
                if (!existingHold.isExpired(now)) {
                    log.debug("좌석이 이미 점유 중: scheduleId={}, seatNo={}, holder={}",
                            scheduleId, seatNumber, existingHold.getUserId());
                    return false;
                }

                // 만료된 경우 업데이트 (이미 락을 잡은 상태라 안전!)
                existingHold.updateHold(
                        userId.asString(),
                        now,
                        now.plus(holdDuration)
                );

                repository.saveAndFlush(existingHold);

                log.info("만료된 좌석 재점유 성공: scheduleId={}, seatNo={}, userId={}",
                        scheduleId, seatNumber, userId.asString());
                return true;
            }

            // 신규 생성
            SeatHoldJpaEntity newHold = new SeatHoldJpaEntity(
                    scheduleId,
                    seatNumber,
                    userId.asString(),
                    now,
                    now.plus(holdDuration)
            );

            repository.saveAndFlush(newHold);

            log.info("좌석 신규 점유 성공: scheduleId={}, seatNo={}, userId={}",
                    scheduleId, seatNumber, userId.asString());
            return true;

        } catch (DataIntegrityViolationException e) {
            log.debug("좌석 점유 실패 - 동시 요청으로 인한 중복: scheduleId={}, seatNo={}",
                    scheduleId, seatNumber);
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
        repository.deleteByScheduleIdAndSeatNumber(  // 괄호 수정
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

    // 만료된 점유 정리 - 스케줄러에서 호출
    @Transactional
    public void cleanupExpiredHolds() {
        try {
            int deleted = repository.deleteExpiredHolds(LocalDateTime.now());
            if (deleted > 0) {
                log.info("만료된 좌석 점유 {}건 정리 완료", deleted);
            }
        } catch (Exception e) {
            log.error("만료된 홀드 정리 실패", e);
            // 실패해도 계속 진행
        }
    }
}