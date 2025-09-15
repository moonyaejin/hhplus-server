package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.ReservationUseCase;
import kr.hhplus.be.server.application.port.in.PaymentUseCase;  // 추가
import kr.hhplus.be.server.application.port.out.*;
import kr.hhplus.be.server.domain.common.Money;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.common.exception.ConcertScheduleNotFoundException;
import kr.hhplus.be.server.domain.concert.ConcertSchedule;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import kr.hhplus.be.server.domain.queue.QueueTokenNotActiveException;
import kr.hhplus.be.server.domain.reservation.*;
import kr.hhplus.be.server.infrastructure.persistence.queue.redis.RedisQueueAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservationService implements ReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final QueuePort queuePort;
    private final PaymentUseCase paymentUseCase;  // WalletPort 대신 PaymentUseCase 사용
    private final ConcertSchedulePort concertSchedulePort;
    private final ReservationDomainService domainService;
    private final SeatHoldPort seatHoldPort;  // Redis 기반 좌석 점유 추가

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

        // 4. Redis를 통한 좌석 점유 시도 (동시성 제어)
        boolean holdSuccess = seatHoldPort.tryHold(
                seatIdentifier,
                userId,
                Duration.ofMinutes(5)  // 5분간 점유
        );

        if (!holdSuccess) {
            // 이미 다른 사용자가 점유 중
            SeatHoldStatus currentHolder = seatHoldPort.getHoldStatus(seatIdentifier);
            if (currentHolder != null && !currentHolder.isHeldBy(userId)) {
                throw new SeatAlreadyAssignedException("이미 다른 사용자가 선택한 좌석입니다");
            }
        }

        try {
            // 5. 애플리케이션 서비스가 필요한 데이터 수집 (Repository 계층)
            List<Reservation> existingReservations = getExistingReservations(seatIdentifier);
            Reservation userExistingReservation = getUserExistingReservation(userId, seatIdentifier);

            // 6. 가격 계산 (도메인 서비스)
            Money price = domainService.calculateSeatPrice(seatIdentifier);

            // 7. 도메인 서비스를 통한 예약 생성 (순수한 비즈니스 로직)
            Reservation reservation = domainService.createTemporaryReservation(
                    userId,
                    seatIdentifier,
                    price,
                    LocalDateTime.now(),
                    existingReservations,
                    userExistingReservation
            );

            // 8. 예약 저장 (Repository 계층)
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

    @Override
    public ConfirmReservationResult confirmReservation(ConfirmReservationCommand command) {

        // 1. 대기열 검증
        validateQueueToken(command.queueToken());
        UserId userId = UserId.ofString(queuePort.userIdOf(command.queueToken()));

        // 2. 예약 조회 및 권한 검증
        Reservation reservation = findAndValidateReservation(command.reservationId(), userId);

        // 3. 도메인 서비스를 통한 확정 가능 여부 검증
        domainService.validateConfirmation(reservation, LocalDateTime.now());

        // 4. PaymentUseCase를 통한 결제 처리 (WalletPort 대신)
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

        return new ConfirmReservationResult(
                reservation.getId().value(),
                paymentResult.balance(),  // 결제 후 잔액
                confirmedAt
        );
    }

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
    // 변경 없음 (기존 코드 유지)

    private void validateQueueToken(String token) {
        if (!queuePort.isActive(token)) {
            Long position = queuePort.getWaitingPosition(token);

            if (position != null) {
                throw new QueueTokenNotActiveException(
                        String.format("대기 중인 토큰입니다. 현재 순번: %d", position)
                );
            }

            throw new QueueTokenExpiredException("유효하지 않거나 만료된 토큰입니다");
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