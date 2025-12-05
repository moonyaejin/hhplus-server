package kr.hhplus.be.server.application.event;

import kr.hhplus.be.server.application.port.in.RankingUseCase;
import kr.hhplus.be.server.infrastructure.kafka.ReservationKafkaProducer;
import kr.hhplus.be.server.infrastructure.kafka.message.ReservationEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationEventListener {

    private final RankingUseCase rankingUseCase;
    private final ReservationKafkaProducer kafkaProducer;


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
                    1
            );

            log.info("[Ranking] 랭킹 업데이트 완료");

        } catch (Exception e) {
            log.warn("⚠️ [Ranking] 랭킹 업데이트 실패 (무시) - scheduleId: {}, error: {}",
                    event.scheduleId(), e.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void updateRankingOnCancel(ReservationCancelledEvent event) {
        try {
            log.info("═══════════════════════════════════════");
            log.info("[Ranking] 취소로 인한 랭킹 차감 시작");
            log.info("예약 ID: {}, 공연 일정 ID: {}", event.reservationId(), event.scheduleId());
            log.info("═══════════════════════════════════════");

            rankingUseCase.decrementReservation(
                    event.scheduleId(),
                    1
            );

            log.info("[Ranking] 랭킹 차감 완료 - reservationId: {}", event.reservationId());

        } catch (Exception e) {
            log.error("⚠️ [Ranking] 랭킹 차감 실패 - reservationId: {}, scheduleId: {}, userId: {}, error: {}",
                    event.reservationId(),
                    event.scheduleId(),
                    event.userId(),
                    e.getMessage(),
                    e);
        }
    }


    /**
     * 예약 확정 시 데이터 플랫폼으로 전송 (Kafka)
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendToDataPlatform(ReservationConfirmedEvent event) {
        try {
            log.info("═══════════════════════════════════════");
            log.info("[Kafka] 예약 확정 정보 발행 시작");
            log.info("예약 ID: {}", event.reservationId());
            log.info("═══════════════════════════════════════");

            // Kafka로 발행
            ReservationEventMessage message = ReservationEventMessage.confirmed(
                    event.reservationId(),
                    event.userId(),
                    event.scheduleId(),
                    event.seatNumber(),
                    event.price(),
                    event.confirmedAt()
            );

            kafkaProducer.sendToDataPlatform(message);

            log.info("[Kafka] 예약 확정 정보 발행 완료 - reservationId: {}", event.reservationId());

        } catch (Exception e) {
            log.error("⚠️ [Kafka] 예약 확정 정보 발행 실패 - reservationId: {}, error: {}",
                    event.reservationId(), e.getMessage(), e);
        }
    }

    /**
     * 예약 취소 시 데이터 플랫폼으로 전송 (Kafka)
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendCancellationToDataPlatform(ReservationCancelledEvent event) {
        try {
            log.info("═══════════════════════════════════════");
            log.info("[Kafka] 예약 취소 정보 발행 시작");
            log.info("예약 ID: {}, 사용자 ID: {}", event.reservationId(), event.userId());
            log.info("═══════════════════════════════════════");

            // Kafka로 발행
            ReservationEventMessage message = ReservationEventMessage.cancelled(
                    event.reservationId(),
                    event.userId(),
                    event.scheduleId(),
                    event.seatNumber(),
                    event.price(),
                    event.cancelledAt()
            );

            kafkaProducer.sendToDataPlatform(message);

            log.info("[Kafka] 예약 취소 정보 발행 완료 - reservationId: {}", event.reservationId());

        } catch (Exception e) {
            log.error("⚠️ [Kafka] 예약 취소 정보 발행 실패 - reservationId: {}, userId: {}, error: {}",
                    event.reservationId(),
                    event.userId(),
                    e.getMessage(),
                    e);
        }
    }
}