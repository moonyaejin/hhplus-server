package kr.hhplus.be.server.infrastructure.kafka.consumer;

import kr.hhplus.be.server.application.event.ReservationConfirmedEvent;
import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import kr.hhplus.be.server.domain.common.Money;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import kr.hhplus.be.server.domain.reservation.*;
import kr.hhplus.be.server.infrastructure.kafka.message.PaymentResultMessage;
import kr.hhplus.be.server.infrastructure.kafka.message.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.support.Acknowledgment;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentResultConsumer 단위 테스트")
class PaymentResultConsumerTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private SeatHoldPort seatHoldPort;

    @Mock
    private QueuePort queuePort;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private PaymentResultConsumer consumer;

    private static final String RESERVATION_ID = "test-reservation-id";
    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final Long SCHEDULE_ID = 1L;
    private static final Integer SEAT_NUMBER = 15;
    private static final Long PRICE = 80_000L;

    private Reservation testReservation;

    @BeforeEach
    void setUp() {
        // PAYMENT_PENDING 상태의 예약 생성
        testReservation = createPaymentPendingReservation();
    }

    private Reservation createPaymentPendingReservation() {
        Reservation reservation = Reservation.restore(
                new ReservationId(RESERVATION_ID),
                UserId.ofString(USER_ID),
                new SeatIdentifier(
                        new ConcertScheduleId(SCHEDULE_ID),
                        new SeatNumber(SEAT_NUMBER)
                ),
                Money.of(PRICE),
                ReservationStatus.PAYMENT_PENDING,
                LocalDateTime.now().minusMinutes(3),
                null,
                0L
        );
        return reservation;
    }

    @Nested
    @DisplayName("결제 성공 시")
    class WhenPaymentSucceeds {

        @Test
        @DisplayName("예약을 확정하고 좌석을 해제하고 이벤트를 발행한다")
        void shouldConfirmReservationAndReleaseSeatAndPublishEvent() {
            // given
            PaymentResultMessage successMessage = PaymentResultMessage.success(
                    RESERVATION_ID,
                    USER_ID,
                    20_000L
            );

            when(reservationRepository.findById(any())).thenReturn(Optional.of(testReservation));
            when(reservationRepository.save(any())).thenReturn(testReservation);

            // when
            consumer.handlePaymentResult(successMessage, acknowledgment);

            // then
            // 1. 예약 저장 확인 (상태가 CONFIRMED로 변경됨)
            ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
            verify(reservationRepository).save(reservationCaptor.capture());
            assertThat(reservationCaptor.getValue().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

            // 2. 좌석 해제 확인
            ArgumentCaptor<SeatIdentifier> seatCaptor = ArgumentCaptor.forClass(SeatIdentifier.class);
            verify(seatHoldPort).release(seatCaptor.capture());
            assertThat(seatCaptor.getValue().scheduleId().value()).isEqualTo(SCHEDULE_ID);
            assertThat(seatCaptor.getValue().seatNumber().value()).isEqualTo(SEAT_NUMBER);

            // 3. 이벤트 발행 확인
            ArgumentCaptor<ReservationConfirmedEvent> eventCaptor =
                    ArgumentCaptor.forClass(ReservationConfirmedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());

            ReservationConfirmedEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.reservationId()).isEqualTo(RESERVATION_ID);
            assertThat(capturedEvent.userId()).isEqualTo(USER_ID);
            assertThat(capturedEvent.scheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(capturedEvent.seatNumber()).isEqualTo(SEAT_NUMBER);
            assertThat(capturedEvent.price()).isEqualTo(PRICE);

            // 4. 커밋 확인
            verify(acknowledgment).acknowledge();
        }
    }

    @Nested
    @DisplayName("결제 실패 시 (잔액 부족)")
    class WhenPaymentFails {

        @Test
        @DisplayName("예약을 PAYMENT_FAILED로 변경하고 좌석을 해제한다")
        void shouldFailReservationAndReleaseSeat() {
            // given
            String failReason = "잔액이 부족합니다";
            PaymentResultMessage failMessage = PaymentResultMessage.insufficientBalance(
                    RESERVATION_ID,
                    USER_ID,
                    failReason
            );

            when(reservationRepository.findById(any())).thenReturn(Optional.of(testReservation));
            when(reservationRepository.save(any())).thenReturn(testReservation);

            // when
            consumer.handlePaymentResult(failMessage, acknowledgment);

            // then
            // 1. 예약 저장 확인 (상태가 PAYMENT_FAILED로 변경됨)
            ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
            verify(reservationRepository).save(reservationCaptor.capture());
            assertThat(reservationCaptor.getValue().getStatus()).isEqualTo(ReservationStatus.PAYMENT_FAILED);

            // 2. 좌석 해제 확인 (다른 사용자가 예약 가능하도록)
            verify(seatHoldPort).release(any(SeatIdentifier.class));

            // 3. 이벤트 발행하지 않음 (실패이므로)
            verify(eventPublisher, never()).publishEvent(any());

            // 4. 커밋 확인
            verify(acknowledgment).acknowledge();
        }
    }

    @Nested
    @DisplayName("예약 조회 실패 시")
    class WhenReservationNotFound {

        @Test
        @DisplayName("커밋하지 않아 재시도된다")
        void shouldNotAcknowledgeForRetry() {
            // given
            PaymentResultMessage message = PaymentResultMessage.success(
                    "non-existent-reservation-id",
                    USER_ID,
                    20_000L
            );

            when(reservationRepository.findById(any())).thenReturn(Optional.empty());

            // when
            consumer.handlePaymentResult(message, acknowledgment);

            // then
            // 1. 좌석 해제하지 않음
            verify(seatHoldPort, never()).release(any());

            // 2. 이벤트 발행하지 않음
            verify(eventPublisher, never()).publishEvent(any());

            // 3. 커밋하지 않음 (재시도 필요)
            verify(acknowledgment, never()).acknowledge();
        }
    }

    @Nested
    @DisplayName("처리 중 예외 발생 시")
    class WhenExceptionOccurs {

        @Test
        @DisplayName("커밋하지 않아 재시도된다")
        void shouldNotAcknowledgeForRetry() {
            // given
            PaymentResultMessage message = PaymentResultMessage.success(
                    RESERVATION_ID,
                    USER_ID,
                    20_000L
            );

            when(reservationRepository.findById(any())).thenReturn(Optional.of(testReservation));
            when(reservationRepository.save(any())).thenThrow(new RuntimeException("DB 오류"));

            // when
            consumer.handlePaymentResult(message, acknowledgment);

            // then
            // 커밋하지 않음 (재시도 필요)
            verify(acknowledgment, never()).acknowledge();
        }
    }

    @Nested
    @DisplayName("메시지 상태 검증")
    class MessageStatusValidation {

        @Test
        @DisplayName("SUCCESS 상태면 handlePaymentSuccess가 호출된다")
        void shouldCallSuccessHandlerForSuccessStatus() {
            // given
            PaymentResultMessage successMessage = new PaymentResultMessage(
                    RESERVATION_ID,
                    USER_ID,
                    PaymentStatus.SUCCESS,
                    20_000L,
                    null,
                    LocalDateTime.now()
            );

            when(reservationRepository.findById(any())).thenReturn(Optional.of(testReservation));
            when(reservationRepository.save(any())).thenReturn(testReservation);

            // when
            consumer.handlePaymentResult(successMessage, acknowledgment);

            // then
            // 이벤트 발행됨 = 성공 핸들러 호출됨
            verify(eventPublisher).publishEvent(any(ReservationConfirmedEvent.class));
        }

        @Test
        @DisplayName("FAILED 상태면 handlePaymentFailure가 호출된다")
        void shouldCallFailureHandlerForFailedStatus() {
            // given
            PaymentResultMessage failMessage = new PaymentResultMessage(
                    RESERVATION_ID,
                    USER_ID,
                    PaymentStatus.FAILED,
                    null,
                    "결제 실패",
                    LocalDateTime.now()
            );

            when(reservationRepository.findById(any())).thenReturn(Optional.of(testReservation));
            when(reservationRepository.save(any())).thenReturn(testReservation);

            // when
            consumer.handlePaymentResult(failMessage, acknowledgment);

            // then
            // 이벤트 발행 안됨 = 실패 핸들러 호출됨
            verify(eventPublisher, never()).publishEvent(any());
            verify(seatHoldPort).release(any()); // 좌석은 해제됨
        }
    }
}