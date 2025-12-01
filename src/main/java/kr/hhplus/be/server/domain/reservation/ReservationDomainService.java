package kr.hhplus.be.server.domain.reservation;

import kr.hhplus.be.server.domain.common.Money;
import kr.hhplus.be.server.domain.common.UserId;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

@Component  // @Service 대신 @Component 사용 (도메인 계층)
public class ReservationDomainService {
    // Repository 의존성 완전 제거!

    /**
     * 좌석 임시 배정 - 순수 도메인 로직
     */
    public Reservation createTemporaryReservation(
            UserId userId,
            SeatIdentifier seatIdentifier,
            Money price,
            LocalDateTime assignedAt,
            List<Reservation> existingReservations,
            Reservation userExistingReservation
    ) {
        // 1. 이미 배정된 좌석인지 확인
        for (Reservation existing : existingReservations) {
            if (existing.getStatus() == ReservationStatus.TEMPORARY_ASSIGNED) {
                throw new SeatAlreadyAssignedException("이미 다른 사용자가 임시 배정한 좌석입니다");
            }
            if (existing.getStatus() == ReservationStatus.CONFIRMED) {
                throw new SeatAlreadyConfirmedException("이미 확정된 좌석입니다");
            }
        }

        // 2. 사용자가 이미 같은 좌석을 임시 배정했는지 확인
        if (userExistingReservation != null &&
                userExistingReservation.getStatus() == ReservationStatus.TEMPORARY_ASSIGNED) {
            throw new DuplicateReservationException("이미 해당 좌석을 임시 배정하였습니다");
        }

        // 3. 새로운 예약 생성
        return Reservation.temporaryAssign(userId, seatIdentifier, price, assignedAt);
    }

    /**
     * 결제 시작 가능 여부 검증 (비동기 결제용)
     */
    public void validatePaymentStart(Reservation reservation, LocalDateTime currentTime) {
        if (!reservation.canStartPayment(currentTime)) {
            if (reservation.isExpired(currentTime)) {
                throw new ReservationExpiredException("만료된 예약은 결제를 시작할 수 없습니다");
            }
            throw new InvalidReservationStateException("현재 상태에서는 결제를 시작할 수 없습니다");
        }
    }

    /**
     * 예약 확정 가능 여부 검증
     */
    public void validateConfirmation(Reservation reservation, LocalDateTime currentTime) {
        if (!reservation.canConfirm(currentTime)) {
            if (reservation.isExpired(currentTime)) {
                throw new ReservationExpiredException("만료된 예약은 확정할 수 없습니다");
            }
            throw new InvalidReservationStateException("현재 상태에서는 예약을 확정할 수 없습니다");
        }
    }

    /**
     * 좌석 가격 계산 - 비즈니스 규칙
     */
    public Money calculateSeatPrice(SeatIdentifier seatIdentifier) {
        int seatNumber = seatIdentifier.seatNumber().value();

        // 가격 정책 (비즈니스 규칙)
        if (seatNumber <= 10) {
            return Money.of(110_000L);  // VIP 좌석
        } else if (seatNumber <= 30) {
            return Money.of(80_000L);   // 일반 좌석
        } else {
            return Money.of(60_000L);   // 뒷좌석
        }
    }

}