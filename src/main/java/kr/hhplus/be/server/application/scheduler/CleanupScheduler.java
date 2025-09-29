package kr.hhplus.be.server.application.scheduler;

import kr.hhplus.be.server.infrastructure.persistence.queue.jpa.repository.QueueTokenJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.ReservationJpaRepository;
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
    private final ReservationJpaRepository reservationRepository;  // 추가

    /**
     * 좌석 점유 만료 처리 - 더 자주 실행 (10초마다)
     * SeatHoldCleanupScheduler 기능 통합
     */
    @Scheduled(fixedDelay = 10000)  // 10초마다 (기존 SeatHoldCleanupScheduler와 동일)
    @Transactional
    public void cleanupExpiredSeatHolds() {
        try {
            LocalDateTime now = LocalDateTime.now();

            // 만료된 좌석 점유 정리
            int deletedSeats = seatHoldRepository.deleteExpiredHolds(now);
            if (deletedSeats > 0) {
                log.info("[좌석점유정리] 만료된 좌석 점유 {}건 삭제 완료", deletedSeats);
            }

        } catch (Exception e) {
            log.error("[좌석점유정리] 처리 중 오류 발생", e);
            // 실패해도 다음 스케줄은 계속 실행되도록 예외를 throw하지 않음
        }
    }

    /**
     * 큐 토큰 만료 처리 - 30초마다
     */
    @Scheduled(fixedDelay = 30000)  // 30초마다
    @Transactional
    public void cleanupExpiredQueueTokens() {
        try {
            LocalDateTime now = LocalDateTime.now();

            // 만료된 활성 토큰을 EXPIRED 상태로 변경
            var expiredTokens = queueTokenRepository.findExpiredActiveTokens(now);
            if (!expiredTokens.isEmpty()) {
                expiredTokens.forEach(token -> token.expire());
                queueTokenRepository.saveAll(expiredTokens);
                log.info("[큐토큰정리] 만료된 활성 토큰 {}건을 EXPIRED 상태로 변경", expiredTokens.size());
            }

        } catch (Exception e) {
            log.error("[큐토큰정리] 처리 중 오류 발생", e);
        }
    }

    /**
     * 임시 예약 만료 처리 - 1분마다
     * TEMPORARY_ASSIGNED 상태가 5분 이상 지난 예약을 EXPIRED로 변경
     */
    @Scheduled(fixedDelay = 60000)  // 1분마다
    @Transactional
    public void cleanupExpiredReservations() {
        try {
            LocalDateTime expiredBefore = LocalDateTime.now().minusMinutes(5);

            // Repository에 메서드가 있다면 사용, 없으면 이 부분은 주석처리
            // var expiredReservations = reservationRepository.findExpiredTemporaryReservations(expiredBefore);
            // if (!expiredReservations.isEmpty()) {
            //     expiredReservations.forEach(reservation -> reservation.expire(LocalDateTime.now()));
            //     reservationRepository.saveAll(expiredReservations);
            //     log.info("[예약정리] 만료된 임시 예약 {}건을 EXPIRED 상태로 변경", expiredReservations.size());
            // }

        } catch (Exception e) {
            log.error("[예약정리] 처리 중 오류 발생", e);
        }
    }

    /**
     * 오래된 데이터 정리 - 매일 새벽 3시
     * 7일 이상 지난 EXPIRED, USED, CANCELLED 데이터 삭제
     */
    @Scheduled(cron = "0 0 3 * * *")  // 매일 새벽 3시
    @Transactional
    public void cleanupOldData() {
        try {
            LocalDateTime oneWeekAgo = LocalDateTime.now().minusDays(7);

            // 오래된 만료/사용/취소 토큰 삭제
            int deletedTokens = queueTokenRepository.deleteOldExpiredTokens(oneWeekAgo);
            if (deletedTokens > 0) {
                log.info("[일일정리] 7일 이상 경과한 토큰 {}건 삭제", deletedTokens);
            }

            // 오래된 예약 데이터 정리 (필요시 구현)
            // int deletedReservations = reservationRepository.deleteOldCancelledReservations(oneWeekAgo);

            log.info("[일일정리] 오래된 데이터 정리 완료");

        } catch (Exception e) {
            log.error("[일일정리] 처리 중 오류 발생", e);
        }
    }

    /**
     * 스케줄러 상태 체크 - 5분마다 (모니터링용)
     */
    @Scheduled(fixedDelay = 300000)  // 5분마다
    public void logSchedulerStatus() {
        try {
            LocalDateTime now = LocalDateTime.now();

            // 현재 활성 상태 카운트 (모니터링용)
            long activeSeatHolds = seatHoldRepository.count();
            // long activeTokens = queueTokenRepository.countByStatus("ACTIVE");

            log.debug("[스케줄러상태] 현재시간: {}, 활성 좌석점유: {}건",
                    now, activeSeatHolds);

        } catch (Exception e) {
            log.error("[스케줄러상태] 체크 중 오류", e);
        }
    }
}