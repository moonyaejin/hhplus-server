package kr.hhplus.be.server.application.scheduler;

import kr.hhplus.be.server.infrastructure.persistence.queue.jpa.repository.QueueTokenJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.SeatHoldJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.scheduler.cleanup.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class CleanupScheduler {

    private final SeatHoldJpaRepository seatHoldRepository;
    private final QueueTokenJpaRepository queueTokenRepository;

    @Scheduled(fixedDelay = 60000)  // 1분마다
    @Transactional
    public void cleanupExpiredData() {
        try {
            LocalDateTime now = LocalDateTime.now();

            // 만료된 좌석 점유 정리
            int deletedSeats = seatHoldRepository.deleteExpiredHolds(now);
            if (deletedSeats > 0) {
                log.info("만료된 좌석 점유 {}건 정리", deletedSeats);
            }

            // 만료된 활성 토큰 정리
            var expiredTokens = queueTokenRepository.findExpiredActiveTokens(now);
            if (!expiredTokens.isEmpty()) {
                expiredTokens.forEach(token -> token.expire());
                log.info("만료된 활성 토큰 {}건 정리", expiredTokens.size());
            }

        } catch (Exception e) {
            log.error("데이터 정리 중 오류 발생", e);
        }
    }
}