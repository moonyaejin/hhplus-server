package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.event.ReservationCancelledEvent;
import kr.hhplus.be.server.application.port.in.ReservationUseCase;
import kr.hhplus.be.server.application.port.in.PaymentUseCase;
import kr.hhplus.be.server.application.port.out.*;
import kr.hhplus.be.server.domain.common.Money;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.common.exception.ConcertScheduleNotFoundException;
import kr.hhplus.be.server.domain.concert.ConcertSchedule;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import kr.hhplus.be.server.domain.queue.QueueTokenNotActiveException;
import kr.hhplus.be.server.domain.reservation.*;
import kr.hhplus.be.server.infrastructure.kafka.message.PaymentRequestMessage;
import kr.hhplus.be.server.infrastructure.kafka.producer.PaymentKafkaProducer;
import kr.hhplus.be.server.infrastructure.redis.lock.RedisDistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 예약 애플리케이션 서비스
 *
 * [분산락 적용]
 * 1. temporaryAssign: 좌석별 락 (동시 예약 방지)
 * 2. confirmReservation: 예약ID별 락 (중복 확정/결제 방지)
 *
 * [관심사 분리]
 * - 핵심 로직: 예약확정(상태변경), 좌석해제, 토큰만료
 * - 결제 처리: Kafka를 통한 비동기 처리
 * - 부가 로직: 랭킹 업데이트, 데이터 플랫폼 전송 (이벤트로 분리)
 *
 * [결제 비동기화]
 * - 기존: paymentUseCase.pay() 동기 호출 (2~3초 블로킹)
 * - 변경: Kafka로 결제 요청 발행 (즉시 응답)
 * - 상태: TEMPORARY_ASSIGNED → PAYMENT_PENDING → CONFIRMED/PAYMENT_FAILED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService implements ReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final QueuePort queuePort;
    private final PaymentUseCase paymentUseCase;  // 환불에만 사용
    private final ConcertSchedulePort concertSchedulePort;
    private final ReservationDomainService domainService;
    private final SeatHoldPort seatHoldPort;
    private final RedisDistributedLock distributedLock;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final PaymentKafkaProducer paymentKafkaProducer;  // 결제 비동기 발행

    /**
     * 좌석 임시 배정
     * - 분산락: lock:reservation:seat:{scheduleId}:{seatNumber}
     * - 범위: Redis 점유 ~ DB 저장
     */
    @Override
    public TemporaryAssignResult temporaryAssign(TemporaryAssignCommand command) {

        // 1. 대기열 검증
        validateQueueToken(command.queueToken());
        UserId userId = UserId.ofString(queuePort.userIdOf(command.queueToken()));

        // 2. 콘서트 스케줄 검증
        Optional<ConcertSchedule> scheduleOpt = concertSchedulePort.findById(
                new ConcertScheduleId(command.concertScheduleId()));
        if (scheduleOpt.isEmpty()) {
            throw new ConcertScheduleNotFoundException("콘서트 스케줄을 찾을 수 없습니다");
        }

        // 3. 좌석 식별자 생성
        SeatIdentifier seatIdentifier = new SeatIdentifier(
                new ConcertScheduleId(command.concertScheduleId()),
                new SeatNumber(command.seatNumber())
        );

        // 분산락 → 트랜잭션 순서로 실행
        String lockKey = RedisDistributedLock.buildSeatLockKey(seatIdentifier);

        return distributedLock.executeWithLock(
                lockKey,
                10L,
                3,
                100L,
                () -> transactionTemplate.execute(status ->
                        executeTemporaryAssign(seatIdentifier, userId)
                )
        );
    }

    /**
     * 좌석 임시 배정 실제 로직
     */
    private TemporaryAssignResult executeTemporaryAssign(
            SeatIdentifier seatIdentifier, UserId userId) {

        // 4. Redis 좌석 점유 시도
        boolean holdSuccess = seatHoldPort.tryHold(
                seatIdentifier, userId, Duration.ofMinutes(5));

        if (!holdSuccess) {
            SeatHoldStatus currentHolder = seatHoldPort.getHoldStatus(seatIdentifier);
            if (currentHolder != null && !currentHolder.isHeldBy(userId)) {
                throw new SeatAlreadyAssignedException("이미 다른 사용자가 선택한 좌석입니다");
            }
        }

        try {
            // 5. DB 조회 (기존 예약들)
            List<Reservation> existingReservations = getExistingReservations(seatIdentifier);
            Reservation userExistingReservation = getUserExistingReservation(userId, seatIdentifier);

            // 6. 가격 계산
            Money price = domainService.calculateSeatPrice(seatIdentifier);

            // 7. 예약 생성
            Reservation reservation = domainService.createTemporaryReservation(
                    userId, seatIdentifier, price, LocalDateTime.now(),
                    existingReservations, userExistingReservation
            );

            // 8. 예약 저장
            reservationRepository.save(reservation);

            return new TemporaryAssignResult(
                    reservation.getId().value(),
                    reservation.getPrice().amount(),
                    reservation.getExpirationTime()
            );

        } catch (Exception e) {
            // 예약 생성 실패 시 Redis 점유 해제
            seatHoldPort.release(seatIdentifier);
            throw e;
        }
    }

    /**
     * 예약 확정 (비동기 결제)
     *
     * [변경사항]
     * - 기존: paymentUseCase.pay() 동기 호출 → 결제 완료 후 응답
     * - 변경: Kafka로 결제 요청 발행 → 즉시 응답 (PAYMENT_PENDING)
     *
     * - 분산락: lock:reservation:confirm:{reservationId}
     * - 범위: 예약 조회 ~ 상태 변경 ~ Kafka 발행
     */
    @Override
    public ConfirmReservationResult confirmReservation(ConfirmReservationCommand command) {

        // 1. 대기열 검증
        validateQueueToken(command.queueToken());
        UserId userId = UserId.ofString(queuePort.userIdOf(command.queueToken()));

        // 2. 예약 ID로 분산락
        String lockKey = "lock:reservation:confirm:" + command.reservationId();

        return distributedLock.executeWithLock(
                lockKey,
                10L,
                3,
                100L,
                () -> transactionTemplate.execute(status ->
                        executeConfirmReservationAsync(command, userId)
                )
        );
    }

    /**
     * 예약 확정 실제 로직 (비동기 결제)
     *
     * 흐름:
     * 1. 예약 조회 및 검증
     * 2. 상태 변경: TEMPORARY_ASSIGNED → PAYMENT_PENDING
     * 3. Kafka로 결제 요청 발행
     * 4. 즉시 응답 반환 (결제 완료 전)
     *
     * 결제 결과는 PaymentResultConsumer에서 처리:
     * - 성공: PAYMENT_PENDING → CONFIRMED
     * - 실패: PAYMENT_PENDING → PAYMENT_FAILED
     */
    private ConfirmReservationResult executeConfirmReservationAsync(
            ConfirmReservationCommand command, UserId userId) {

        // 1. 예약 조회 및 권한 검증
        Reservation reservation = findAndValidateReservation(command.reservationId(), userId);

        // 2. 도메인 서비스를 통한 확정 가능 여부 검증
        domainService.validatePaymentStart(reservation, LocalDateTime.now());

        // 3. 상태 변경: TEMPORARY_ASSIGNED → PAYMENT_PENDING
        reservation.startPayment();
        reservationRepository.save(reservation);

        // 4. Kafka로 결제 요청 발행
        PaymentRequestMessage paymentRequest = PaymentRequestMessage.of(
                reservation.getId().value(),
                userId.asString(),
                reservation.getPrice().amount(),
                command.idempotencyKey(),
                reservation.getSeatIdentifier().scheduleId().value(),
                reservation.getSeatIdentifier().seatNumber().value()
        );
        paymentKafkaProducer.sendPaymentRequest(paymentRequest);

        log.info("결제 요청 발행 완료 - reservationId: {}, status: PAYMENT_PENDING",
                reservation.getId().value());

        // 5. 대기열 토큰 만료 (결제 요청이 발행되면 토큰은 더 이상 필요 없음)
        queuePort.expire(command.queueToken());

        // 6. 즉시 응답 반환 (결제 완료 전)
        return new ConfirmReservationResult(
                reservation.getId().value(),
                null,
                null,
                "PAYMENT_PENDING"
        );
    }

    /**
     * 예약 취소
     * - 분산락: lock:reservation:cancel:{reservationId}
     * - 범위: 예약 조회 ~ 환불 ~ 취소 ~ 이벤트 발행
     * - 중요: 중복 취소 방지
     */
    @Override
    public CancelReservationResult cancelReservation(CancelReservationCommand command) {

        // 1. 사용자 ID 추출 (대기열 검증은 생략 - 취소는 언제든 가능)
        UserId userId = UserId.ofString(command.userId());

        // 2. 예약 ID로 분산락 (중복 취소 방지)
        String lockKey = "lock:reservation:cancel:" + command.reservationId();

        return distributedLock.executeWithLock(
                lockKey,
                10L,
                3,
                100L,
                () -> transactionTemplate.execute(status ->
                        executeCancelReservation(command, userId)
                )
        );
    }

    /**
     * 예약 취소 실제 로직 (분산락 + 트랜잭션 내부)
     */
    private CancelReservationResult executeCancelReservation(
            CancelReservationCommand command, UserId userId) {

        // 1. 예약 조회 및 권한 검증
        Reservation reservation = findAndValidateReservation(command.reservationId(), userId);

        // 2. 취소 가능 여부 검증 (CONFIRMED 상태만 취소 가능)
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new InvalidReservationStateException(
                    "확정된 예약만 취소할 수 있습니다. 현재 상태: " + reservation.getStatus());
        }

        // 3. 환불 처리 (동기 - 취소는 빈도가 낮으므로 동기 유지)
        PaymentUseCase.RefundCommand refundCommand = new PaymentUseCase.RefundCommand(
                userId.asString(),
                reservation.getPrice().amount(),
                "refund-" + command.reservationId() + "-" + System.currentTimeMillis()
        );

        PaymentUseCase.BalanceResult refundResult = paymentUseCase.refund(refundCommand);

        // 4. 예약 상태 변경 → CANCELLED
        LocalDateTime cancelledAt = LocalDateTime.now();
        reservation.cancel(cancelledAt);
        reservationRepository.save(reservation);

        // 5. 예약 취소 이벤트 발행
        eventPublisher.publishEvent(
                ReservationCancelledEvent.of(
                        reservation.getId().value(),
                        reservation.getUserId().asString(),
                        reservation.getSeatIdentifier().scheduleId().value(),
                        reservation.getSeatIdentifier().seatNumber().value(),
                        reservation.getPrice().amount(),
                        cancelledAt
                )
        );
        log.info("예약 취소 이벤트 발행 완료 - reservationId: {}", reservation.getId().value());

        return new CancelReservationResult(
                reservation.getId().value(),
                reservation.getPrice().amount(),
                cancelledAt
        );
    }

    /**
     * 예약 조회
     */
    @Transactional(readOnly = true)
    @Override
    public ReservationInfo getReservation(ReservationQuery query) {
        Reservation reservation = findAndValidateReservation(query.reservationId(),
                UserId.ofString(query.userId()));

        return new ReservationInfo(
                reservation.getId().value(),
                reservation.getUserId().asString(),
                reservation.getSeatIdentifier().scheduleId().value(),
                reservation.getSeatIdentifier().seatNumber().value(),
                reservation.getStatus().name(),
                reservation.getPrice().amount(),
                reservation.getTemporaryAssignedAt(),
                reservation.getConfirmedAt(),
                reservation.getExpirationTime()
        );
    }


    private void validateQueueToken(String token) {
        try {
            if (!queuePort.isActive(token)) {
                Long position = queuePort.getWaitingPosition(token);

                if (position != null) {
                    throw new QueueTokenNotActiveException(
                            String.format("대기 중인 토큰입니다. 현재 순번: %d", position)
                    );
                }

                throw new QueueTokenExpiredException("유효하지 않거나 만료된 토큰입니다");
            }
        } catch (Exception e) {
            throw e;
        }
    }

    private Reservation findAndValidateReservation(String reservationId, UserId userId) {
        Optional<Reservation> reservationOpt = reservationRepository.findById(new ReservationId(reservationId));
        if (reservationOpt.isEmpty()) {
            throw new ReservationNotFoundException("예약을 찾을 수 없습니다");
        }

        Reservation reservation = reservationOpt.get();
        if (!reservation.getUserId().equals(userId)) {
            throw new UnauthorizedReservationAccessException("해당 예약에 접근할 권한이 없습니다");
        }

        return reservation;
    }

    private List<Reservation> getExistingReservations(SeatIdentifier seatIdentifier) {
        List<Reservation> existingReservations = new ArrayList<>();

        List<Reservation> temporaryReservations = reservationRepository.findBySeatIdentifierAndStatus(
                seatIdentifier,
                ReservationStatus.TEMPORARY_ASSIGNED
        );
        existingReservations.addAll(temporaryReservations);

        List<Reservation> confirmedReservations = reservationRepository.findBySeatIdentifierAndStatus(
                seatIdentifier,
                ReservationStatus.CONFIRMED
        );
        existingReservations.addAll(confirmedReservations);

        return existingReservations;
    }

    private Reservation getUserExistingReservation(UserId userId, SeatIdentifier seatIdentifier) {
        Optional<Reservation> userReservationOpt = reservationRepository.findByUserIdAndSeatIdentifier(
                userId, seatIdentifier);
        return userReservationOpt.orElse(null);
    }
}