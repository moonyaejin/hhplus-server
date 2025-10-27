package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.RankingUseCase;
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
import kr.hhplus.be.server.infrastructure.redis.lock.RedisDistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 예약 애플리케이션 서비스
 *
 * [분산락 적용 포인트]
 * 1. temporaryAssign: 좌석별 락 (동시 예약 방지)
 * 2. confirmReservation: 예약ID별 락 (중복 확정/결제 방지)
 *
 * [트랜잭션 제어]
 * - 클래스 레벨 @Transactional 제거 (수동 제어)
 * - TransactionTemplate으로 락 안에서 트랜잭션 시작
 * - 순서: 락 획득 → 트랜잭션 시작 → 작업 → 트랜잭션 커밋 → 락 해제
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService implements ReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final QueuePort queuePort;
    private final PaymentUseCase paymentUseCase;
    private final ConcertSchedulePort concertSchedulePort;
    private final ReservationDomainService domainService;
    private final SeatHoldPort seatHoldPort;
    private final RedisDistributedLock distributedLock;
    private final TransactionTemplate transactionTemplate;
    private final RankingUseCase rankingUseCase;

    /**
     * 좌석 임시 배정
     * - 분산락: lock:reservation:seat:{scheduleId}:{seatNumber}
     * - 범위: Redis 점유 ~ DB 저장
     */
    @Override
    public TemporaryAssignResult temporaryAssign(TemporaryAssignCommand command) {

        // 1. 대기열 검증 (락/트랜잭션 불필요)
        validateQueueToken(command.queueToken());
        UserId userId = UserId.ofString(queuePort.userIdOf(command.queueToken()));

        // 2. 콘서트 스케줄 검증 (락/트랜잭션 불필요)
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

        // 4~8. 분산락 → 트랜잭션 순서로 실행
        String lockKey = RedisDistributedLock.buildSeatLockKey(seatIdentifier);

        return distributedLock.executeWithLock(
                lockKey,
                10L,        // TTL: 10초 (충분한 여유)
                3,          // 최대 3번 재시도
                100L,       // 100ms 대기 후 재시도
                () -> transactionTemplate.execute(status ->
                        executeTemporaryAssign(seatIdentifier, userId)
                )
        );
    }

    /**
     * 좌석 임시 배정 실제 로직 (분산락 + 트랜잭션 내부)
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

            // 6. 가격 계산 (도메인 서비스)
            Money price = domainService.calculateSeatPrice(seatIdentifier);

            // 7. 예약 생성 (도메인 서비스)
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
     * 예약 확정
     * - 분산락: lock:reservation:confirm:{reservationId}
     * - 범위: 예약 조회 ~ 결제 ~ 확정 ~ Redis/Queue 정리
     * - 중요: 중복 결제 방지
     */
    @Override
    public ConfirmReservationResult confirmReservation(ConfirmReservationCommand command) {

        // 1. 대기열 검증 (락/트랜잭션 불필요)
        validateQueueToken(command.queueToken());
        UserId userId = UserId.ofString(queuePort.userIdOf(command.queueToken()));

        // 2. 예약 ID로 분산락 (중복 확정/결제 방지)
        String lockKey = "lock:reservation:confirm:" + command.reservationId();

        return distributedLock.executeWithLock(
                lockKey,
                10L,        // TTL: 10초
                3,          // 최대 3번 재시도
                100L,       // 100ms 대기 후 재시도
                () -> transactionTemplate.execute(status ->
                        executeConfirmReservation(command, userId)
                )
        );
    }

    /**
     * 예약 확정 실제 로직 (분산락 + 트랜잭션 내부)
     */
    private ConfirmReservationResult executeConfirmReservation(
            ConfirmReservationCommand command, UserId userId) {

        // 2. 예약 조회 및 권한 검증
        Reservation reservation = findAndValidateReservation(command.reservationId(), userId);

        // 3. 도메인 서비스를 통한 확정 가능 여부 검증
        domainService.validateConfirmation(reservation, LocalDateTime.now());

        // 4. PaymentUseCase를 통한 결제 처리
        PaymentUseCase.PaymentCommand paymentCommand = new PaymentUseCase.PaymentCommand(
                userId.asString(),
                reservation.getPrice().amount(),
                command.idempotencyKey()
        );

        PaymentUseCase.BalanceResult paymentResult = paymentUseCase.pay(paymentCommand);

        // 5. 예약 확정 (도메인 객체의 비즈니스 메서드)
        LocalDateTime confirmedAt = LocalDateTime.now();
        reservation.confirm(confirmedAt);
        reservationRepository.save(reservation);

        // 6. Redis에서 좌석 점유 해제
        seatHoldPort.release(reservation.getSeatIdentifier());

        // 7. 대기열 토큰 만료
        queuePort.expire(command.queueToken());

        // 8. 랭킹 시스템에 판매 기록
        try {
            CompletableFuture.runAsync(() -> {
                rankingUseCase.trackReservation(
                        reservation.getSeatIdentifier().scheduleId().value(),
                        1  // 예약된 좌석 수
                );
            });
            log.info("랭킹 업데이트 완료 - scheduleId: {}",
                    reservation.getSeatIdentifier().scheduleId().value());
        } catch (Exception e) {
            log.warn("랭킹 업데이트 실패 (무시): {}", e.getMessage());
        }

        // ConfirmReservationResult 객체 생성하여 반환
        return new ConfirmReservationResult(
                reservation.getId().value(),
                paymentResult.balance(),
                confirmedAt
        );
    }


    /**
     * 예약 조회 (읽기 전용)
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

    // === Private Helper Methods ===

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