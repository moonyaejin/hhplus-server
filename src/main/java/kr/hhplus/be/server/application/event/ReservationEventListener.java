package kr.hhplus.be.server.application.event;

import kr.hhplus.be.server.application.port.in.RankingUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 예약 이벤트 리스너
 *
 * - 예약 확정 완료 시 부가 기능들을 수행
 * - 트랜잭션 커밋 후 비동기 실행
 * - 실패해도 핵심 비즈니스(예약 확정)에는 영향 없음
 *
 * 관심사 분리:
 * - 핵심 로직(예약 확정): ReservationService에서 트랜잭션으로 처리
 * - 부가 로직: EventListener에서 비동기로 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventListener {

    private final RankingUseCase rankingUseCase;

    // 예약 확정 시 랭킹 시스템 업데이트
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void updateRanking(ReservationConfirmedEvent event) {
        try {
            log.info("═══════════════════════════════════════");
            log.info("[Ranking] 랭킹 업데이트 시작");
            log.info("공연 일정 ID: {}", event.scheduleId());
            log.info("═══════════════════════════════════════");

            rankingUseCase.trackReservation(
                    event.scheduleId(),
                    1  // 예약된 좌석 수
            );

            log.info("[Ranking] 랭킹 업데이트 완료");

        } catch (Exception e) {
            // 실패해도 예약 확정은 이미 성공
            log.warn("⚠️  [Ranking] 랭킹 업데이트 실패 (무시) - scheduleId: {}, error: {}",
                    event.scheduleId(), e.getMessage());
        }
    }

    /**
     * 예약 확정 완료 시 데이터 플랫폼으로 전송
     *
     * @param event 예약 확정 이벤트
     *
     * 실행 시점: 트랜잭션 커밋 후
     * 실행 방식: 비동기
     * 실패 처리: 로그만 남기고 무시
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendToDataPlatform(ReservationConfirmedEvent event) {
        try {
            log.info("═══════════════════════════════════════");
            log.info("[Data Platform] 예약 정보 전송 시작");
            log.info("예약 ID: {}", event.reservationId());
            log.info("═══════════════════════════════════════");

            // Mock API 호출 (실제로는 외부 API 또는 메시지 큐)
            sendMockDataPlatformApi(event);

            log.info("[Data Platform] 전송 완료");

        } catch (Exception e) {
            // 실패해도 핵심 비즈니스(예약 확정)는 이미 성공했으므로 무시
            log.warn("[Data Platform] 전송 실패 (무시) - reservationId: {}, error: {}",
                    event.reservationId(), e.getMessage());
        }
    }

    /**
     * Mock 데이터 플랫폼 API 호출
     *
     * <p>실제 구현 시:
     * - REST API 호출: RestTemplate, WebClient 등
     * - 메시지 큐: Kafka, RabbitMQ 등
     * - 배치 처리: 실패 건 재시도 로직 등
     */
    private void sendMockDataPlatformApi(ReservationConfirmedEvent event) {
        log.info("""
            [ Mock Data Platform API 호출 ]
            예약 ID      : {}
            사용자 ID    : {}
             공연 일정 ID : {}
            좌석 번호    : {}
             결제 금액    : {:,}원
            확정 시각    : {}
            """,
                event.reservationId(),
                event.userId(),
                event.scheduleId(),
                event.seatNumber(),
                event.price(),
                event.confirmedAt()
        );
    }
}