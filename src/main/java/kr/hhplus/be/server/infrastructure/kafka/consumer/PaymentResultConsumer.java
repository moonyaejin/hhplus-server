package kr.hhplus.be.server.infrastructure.kafka.consumer;

import kr.hhplus.be.server.application.event.ReservationConfirmedEvent;
import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import kr.hhplus.be.server.domain.reservation.Reservation;
import kr.hhplus.be.server.domain.reservation.ReservationId;
import kr.hhplus.be.server.domain.reservation.ReservationRepository;
import kr.hhplus.be.server.infrastructure.kafka.message.PaymentResultMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 결제 결과 Consumer
 *
 * Topic: payment-results
 * Group: reservation-updater
 *
 * 역할:
 * 1. 결제 결과 메시지 수신
 * 2. 예약 상태 업데이트 (CONFIRMED or PAYMENT_FAILED)
 * 3. 좌석 점유 해제
 * 4. 성공 시 이벤트 발행 (랭킹, 데이터 플랫폼)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentResultConsumer {

    private final ReservationRepository reservationRepository;
    private final SeatHoldPort seatHoldPort;
    private final QueuePort queuePort;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    @KafkaListener(
            topics = "payment-results",
            groupId = "reservation-updater",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handlePaymentResult(PaymentResultMessage message, Acknowledgment ack) {
        log.info("═══════════════════════════════════════════════════");
        log.info("결제 결과 수신");
        log.info("예약 ID: {}", message.reservationId());
        log.info("결과: {}", message.status());
        log.info("═══════════════════════════════════════════════════");

        try {
            // 1. 예약 조회
            Reservation reservation = reservationRepository
                    .findById(new ReservationId(message.reservationId()))
                    .orElseThrow(() -> new IllegalStateException(
                            "예약을 찾을 수 없습니다: " + message.reservationId()));

            if (message.isSuccess()) {
                handlePaymentSuccess(reservation, message);
            } else {
                handlePaymentFailure(reservation, message);
            }

            // 커밋
            ack.acknowledge();

        } catch (Exception e) {
            log.error("결제 결과 처리 실패 - reservationId: {}, error: {}",
                    message.reservationId(), e.getMessage(), e);
            // 재시도를 위해 커밋하지 않음
        }
    }

    /**
     * 결제 성공 처리
     */
    private void handlePaymentSuccess(Reservation reservation, PaymentResultMessage message) {
        LocalDateTime confirmedAt = message.processedAt();

        // 1. 예약 확정
        reservation.confirm(confirmedAt);
        reservationRepository.save(reservation);

        // 2. 좌석 점유 해제
        seatHoldPort.release(reservation.getSeatIdentifier());

        // 3. 이벤트 발행 (랭킹, 데이터 플랫폼)
        eventPublisher.publishEvent(
                ReservationConfirmedEvent.of(
                        reservation.getId().value(),
                        reservation.getUserId().asString(),
                        reservation.getSeatIdentifier().scheduleId().value(),
                        reservation.getSeatIdentifier().seatNumber().value(),
                        reservation.getPrice().amount(),
                        confirmedAt
                )
        );

        log.info("예약 확정 완료 - reservationId: {}, 잔액: {:,}원",
                reservation.getId().value(), message.balance());
    }

    /**
     * 결제 실패 처리
     */
    private void handlePaymentFailure(Reservation reservation, PaymentResultMessage message) {
        // 1. 예약 상태 변경 → PAYMENT_FAILED
        reservation.failPayment(message.failReason());
        reservationRepository.save(reservation);

        // 2. 좌석 점유 해제 (다른 사용자가 예약 가능하도록)
        seatHoldPort.release(reservation.getSeatIdentifier());

        log.warn("결제 실패로 예약 취소 - reservationId: {}, 사유: {}",
                reservation.getId().value(), message.failReason());
    }
}