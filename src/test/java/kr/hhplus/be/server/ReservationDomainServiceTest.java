package kr.hhplus.be.server;

import kr.hhplus.be.server.domain.common.Money;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import kr.hhplus.be.server.domain.reservation.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationDomainServiceTest {

    private ReservationDomainService domainService;
    private UserId userId;
    private SeatIdentifier seatIdentifier;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        domainService = new ReservationDomainService();
        userId = UserId.generate();
        seatIdentifier = new SeatIdentifier(
                new ConcertScheduleId(1L),
                new SeatNumber(10)
        );
        now = LocalDateTime.now();
    }

    @Test
    @DisplayName("빈 좌석에 대해 임시 예약을 생성할 수 있다")
    void createTemporaryReservation_Success() {
        // given
        List<Reservation> existingReservations = new ArrayList<>();
        Money price = Money.of(80_000L);

        // when
        Reservation reservation = domainService.createTemporaryReservation(
                userId, seatIdentifier, price, now,
                existingReservations, null
        );

        // then
        assertThat(reservation).isNotNull();
        assertThat(reservation.getUserId()).isEqualTo(userId);
        assertThat(reservation.getSeatIdentifier()).isEqualTo(seatIdentifier);
        assertThat(reservation.getPrice()).isEqualTo(price);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.TEMPORARY_ASSIGNED);
    }

    @Test
    @DisplayName("이미 다른 사용자가 임시 배정한 좌석은 예약할 수 없다")
    void createTemporaryReservation_AlreadyAssigned() {
        // given
        UserId otherUserId = UserId.generate();
        Reservation existingReservation = Reservation.temporaryAssign(
                otherUserId, seatIdentifier, Money.of(80_000L), now.minusMinutes(1)
        );
        List<Reservation> existingReservations = List.of(existingReservation);

        // when & then
        assertThatThrownBy(() -> domainService.createTemporaryReservation(
                userId, seatIdentifier, Money.of(80_000L), now,
                existingReservations, null
        ))
                .isInstanceOf(SeatAlreadyAssignedException.class)
                .hasMessage("이미 다른 사용자가 임시 배정한 좌석입니다");
    }

    @Test
    @DisplayName("이미 확정된 좌석은 예약할 수 없다")
    void createTemporaryReservation_AlreadyConfirmed() {
        // given
        Reservation confirmedReservation = Reservation.restore(
                ReservationId.generate(),
                UserId.generate(),
                seatIdentifier,
                Money.of(80_000L),
                ReservationStatus.CONFIRMED,
                now.minusMinutes(10),
                now.minusMinutes(5),
                0L
        );
        List<Reservation> existingReservations = List.of(confirmedReservation);

        // when & then
        assertThatThrownBy(() -> domainService.createTemporaryReservation(
                userId, seatIdentifier, Money.of(80_000L), now,
                existingReservations, null
        ))
                .isInstanceOf(SeatAlreadyConfirmedException.class)
                .hasMessage("이미 확정된 좌석입니다");
    }

    @Test
    @DisplayName("같은 사용자가 이미 임시 배정한 좌석은 중복 예약할 수 없다")
    void createTemporaryReservation_DuplicateByUser() {
        // given
        Reservation userExistingReservation = Reservation.temporaryAssign(
                userId, seatIdentifier, Money.of(80_000L), now.minusMinutes(1)
        );

        // when & then
        assertThatThrownBy(() -> domainService.createTemporaryReservation(
                userId, seatIdentifier, Money.of(80_000L), now,
                new ArrayList<>(), userExistingReservation
        ))
                .isInstanceOf(DuplicateReservationException.class)
                .hasMessage("이미 해당 좌석을 임시 배정하였습니다");
    }

    @Test
    @DisplayName("좌석 가격을 올바르게 계산한다 - VIP 좌석")
    void calculateSeatPrice_VIP() {
        // given
        SeatIdentifier vipSeat = new SeatIdentifier(
                new ConcertScheduleId(1L),
                new SeatNumber(5)  // 1-10번은 VIP
        );

        // when
        Money price = domainService.calculateSeatPrice(vipSeat);

        // then
        assertThat(price.amount()).isEqualTo(110_000L);
    }

    @Test
    @DisplayName("좌석 가격을 올바르게 계산한다 - 일반 좌석")
    void calculateSeatPrice_Regular() {
        // given
        SeatIdentifier regularSeat = new SeatIdentifier(
                new ConcertScheduleId(1L),
                new SeatNumber(20)  // 11-30번은 일반
        );

        // when
        Money price = domainService.calculateSeatPrice(regularSeat);

        // then
        assertThat(price.amount()).isEqualTo(80_000L);
    }

    @Test
    @DisplayName("좌석 가격을 올바르게 계산한다 - 뒷좌석")
    void calculateSeatPrice_Back() {
        // given
        SeatIdentifier backSeat = new SeatIdentifier(
                new ConcertScheduleId(1L),
                new SeatNumber(40)  // 31-50번은 뒷좌석
        );

        // when
        Money price = domainService.calculateSeatPrice(backSeat);

        // then
        assertThat(price.amount()).isEqualTo(60_000L);
    }

    @Disabled("비동기 결제로 변경 - 별도 Kafka 통합 테스트로 대체 예정")
    @Test
    @DisplayName("확정 가능한 예약에 대해 검증을 통과한다")
    void validateConfirmation_Success() {
        // given
        Reservation reservation = Reservation.temporaryAssign(
                userId, seatIdentifier, Money.of(80_000L), now.minusMinutes(2)
        );

        // when & then - 예외가 발생하지 않음
        domainService.validateConfirmation(reservation, now);
    }

    @Test
    @DisplayName("만료된 예약은 확정할 수 없다")
    void validateConfirmation_Expired() {
        // given
        Reservation reservation = Reservation.temporaryAssign(
                userId, seatIdentifier, Money.of(80_000L), now.minusMinutes(10)
        );

        // when & then
        assertThatThrownBy(() -> domainService.validateConfirmation(reservation, now))
                .isInstanceOf(ReservationExpiredException.class)
                .hasMessage("만료된 예약은 확정할 수 없습니다");
    }
}