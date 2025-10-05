package kr.hhplus.be.server.infrastructure.persistence.reservation.mysql;

import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.entity.SeatHoldJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.SeatHoldJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SeatHoldTransactionService {

    private final SeatHoldJpaRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryHold(Long scheduleId, Integer seatNumber, String userId,
                           LocalDateTime heldAt, LocalDateTime expiresAt) {
        try {
            // 기존 홀드 확인
            Optional<SeatHoldJpaEntity> existing =
                    repository.findByScheduleIdAndSeatNumber(scheduleId, seatNumber);

            if (existing.isPresent() && !existing.get().isExpired()) {
                return false; // 아직 만료되지 않음
            }

            if (existing.isPresent()) {
                // 만료된 경우 삭제
                repository.delete(existing.get());
                repository.flush();
            }

            // 새로운 홀드 생성
            SeatHoldJpaEntity entity = new SeatHoldJpaEntity(
                    scheduleId, seatNumber, userId, heldAt, expiresAt
            );

            repository.save(entity);
            return true;

        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}