package kr.hhplus.be.server.application.scheduler;

import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import kr.hhplus.be.server.domain.reservation.Reservation;
import kr.hhplus.be.server.domain.reservation.ReservationRepository;
import kr.hhplus.be.server.domain.reservation.ReservationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제 타임아웃 스케줄러
 *
 * 역할:
 * - PAYMENT_PENDING 상태가 5분 이상 지속된 예약을 만료 처리
 * - 좌석 점유 해제
 *
 * 실행 주기: 1분마다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTimeoutScheduler {

    private static final int PAYMENT_TIMEOUT_MINUTES = 5;

    private final ReservationRepository reservationRepository;
    private final SeatHoldPort seatHoldPort;

    /**
     * PAYMENT_PENDING 타임아웃 처리
     *
     * 조건: PAYMENT_PENDING 상태 + paymentRequestedAt이 5분 이상 경과
     * 처리: PAYMENT_PENDING → EXPIRED
     */
    @Scheduled(fixedRate = 60000)  // 1분마다 실행
    @Transactional
    public void handlePaymentTimeout() {
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(PAYMENT_TIMEOUT_MINUTES);

        log.debug("결제 타임아웃 체크 시작 - threshold: {}", timeoutThreshold);

        // PAYMENT_PENDING 상태인 예약들 중 타임아웃된 것들 조회
        List<Reservation> timedOutReservations = reservationRepository
                .findByStatusAndPaymentRequestedAtBefore(
                        ReservationStatus.PAYMENT_PENDING,
                        timeoutThreshold
                );

        if (timedOutReservations.isEmpty()) {
            return;
        }

        log.info("결제 타임아웃 처리 대상: {}건", timedOutReservations.size());

        for (Reservation reservation : timedOutReservations) {
            try {
                processTimeout(reservation);
            } catch (Exception e) {
                log.error("결제 타임아웃 처리 실패 - reservationId: {}, error: {}",
                        reservation.getId().value(), e.getMessage(), e);
            }
        }
    }

    private void processTimeout(Reservation reservation) {
        log.warn("결제 타임아웃 - reservationId: {}, userId: {}, 요청시간: {}",
                reservation.getId().value(),
                reservation.getUserId().asString(),
                reservation.getPaymentRequestedAt());

        // 1. 예약 만료 처리
        reservation.expire(LocalDateTime.now());
        reservationRepository.save(reservation);

        // 2. 좌석 점유 해제
        seatHoldPort.release(reservation.getSeatIdentifier());

        log.info("결제 타임아웃 처리 완료 - reservationId: {}", reservation.getId().value());
    }
}