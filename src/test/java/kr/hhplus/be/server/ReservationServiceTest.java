package kr.hhplus.be.server;

import kr.hhplus.be.server.application.event.ReservationConfirmedEvent;
import kr.hhplus.be.server.application.port.in.PaymentUseCase;
import kr.hhplus.be.server.application.port.in.ReservationUseCase.*;
import kr.hhplus.be.server.application.port.out.*;
import kr.hhplus.be.server.application.service.ReservationService;
import kr.hhplus.be.server.domain.common.Money;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.concert.ConcertSchedule;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import kr.hhplus.be.server.domain.concert.ConcertId;
import kr.hhplus.be.server.domain.reservation.*;
import kr.hhplus.be.server.infrastructure.redis.lock.RedisDistributedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock private ReservationRepository reservationRepository;
    @Mock private QueuePort queuePort;
    @Mock private PaymentUseCase paymentUseCase;
    @Mock private ConcertSchedulePort concertSchedulePort;
    @Mock private ReservationDomainService domainService;
    @Mock private SeatHoldPort seatHoldPort;
    @Mock private RedisDistributedLock distributedLock;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ReservationService reservationService;

    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String QUEUE_TOKEN = "test-queue-token";
    private static final Long SCHEDULE_ID = 1L;
    private static final Integer SEAT_NUMBER = 10;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(
                reservationRepository,
                queuePort,
                paymentUseCase,
                concertSchedulePort,
                domainService,
                seatHoldPort,
                distributedLock,
                transactionTemplate,
                eventPublisher
        );

        // 분산락과 트랜잭션 Mock 동작 설정
        lenient().when(distributedLock.executeWithLock(anyString(), anyLong(), anyInt(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    var supplier = invocation.getArgument(4, java.util.function.Supplier.class);
                    return supplier.get();
                });

        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(invocation -> {
                    var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                    return callback.doInTransaction(null);
                });
    }

    @Test
    @DisplayName("임시 좌석 배정 - 성공 케이스")
    void temporaryAssign_Success() {
        // given
        TemporaryAssignCommand command = new TemporaryAssignCommand(
                QUEUE_TOKEN, SCHEDULE_ID, SEAT_NUMBER
        );

        when(queuePort.isActive(QUEUE_TOKEN)).thenReturn(true);
        when(queuePort.userIdOf(QUEUE_TOKEN)).thenReturn(USER_ID);

        ConcertSchedule schedule = new ConcertSchedule(
                new ConcertScheduleId(SCHEDULE_ID),
                new ConcertId(1L),
                LocalDate.now(),
                50
        );
        when(concertSchedulePort.findById(any())).thenReturn(Optional.of(schedule));
        when(seatHoldPort.tryHold(any(), any(), any())).thenReturn(true);

        Money price = Money.of(80_000L);
        when(domainService.calculateSeatPrice(any())).thenReturn(price);

        Reservation mockReservation = Reservation.temporaryAssign(
                UserId.ofString(USER_ID),
                new SeatIdentifier(new ConcertScheduleId(SCHEDULE_ID), new SeatNumber(SEAT_NUMBER)),
                price,
                LocalDateTime.now()
        );
        when(domainService.createTemporaryReservation(any(), any(), any(), any(), any(), any()))
                .thenReturn(mockReservation);
        when(reservationRepository.save(any())).thenReturn(mockReservation);

        // when
        TemporaryAssignResult result = reservationService.temporaryAssign(command);

        // then
        assertThat(result).isNotNull();
        assertThat(result.price()).isEqualTo(80_000L);
        assertThat(result.reservationId()).isNotNull();

        verify(distributedLock).executeWithLock(anyString(), anyLong(), anyInt(), anyLong(), any());
        verify(transactionTemplate).execute(any());
        verify(seatHoldPort).tryHold(any(SeatIdentifier.class), any(UserId.class), eq(Duration.ofMinutes(5)));
        verify(reservationRepository).save(any(Reservation.class));
    }

    @Test
    @DisplayName("임시 좌석 배정 - 토큰 만료시 실패")
    void temporaryAssign_TokenExpired() {
        // given
        TemporaryAssignCommand command = new TemporaryAssignCommand(
                QUEUE_TOKEN, SCHEDULE_ID, SEAT_NUMBER
        );

        when(queuePort.isActive(QUEUE_TOKEN)).thenReturn(false);
        when(queuePort.getWaitingPosition(QUEUE_TOKEN)).thenReturn(null);

        // when & then
        assertThatThrownBy(() -> reservationService.temporaryAssign(command))
                .isInstanceOf(QueueTokenExpiredException.class)
                .hasMessage("유효하지 않거나 만료된 토큰입니다");

        verify(distributedLock, never()).executeWithLock(any(), anyLong(), anyInt(), anyLong(), any());
        verify(seatHoldPort, never()).tryHold(any(), any(), any());
    }

    @Test
    @DisplayName("임시 좌석 배정 - 이미 점유된 좌석")
    void temporaryAssign_SeatAlreadyHeld() {
        // given
        TemporaryAssignCommand command = new TemporaryAssignCommand(
                QUEUE_TOKEN, SCHEDULE_ID, SEAT_NUMBER
        );

        when(queuePort.isActive(QUEUE_TOKEN)).thenReturn(true);
        when(queuePort.userIdOf(QUEUE_TOKEN)).thenReturn(USER_ID);

        ConcertSchedule schedule = new ConcertSchedule(
                new ConcertScheduleId(SCHEDULE_ID),
                new ConcertId(1L),
                LocalDate.now(),
                50
        );
        when(concertSchedulePort.findById(any())).thenReturn(Optional.of(schedule));
        when(seatHoldPort.tryHold(any(), any(), any())).thenReturn(false);

        SeatHoldStatus otherUserHold = new SeatHoldStatus(
                UserId.generate(),
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusMinutes(4)
        );
        when(seatHoldPort.getHoldStatus(any())).thenReturn(otherUserHold);

        // when & then
        assertThatThrownBy(() -> reservationService.temporaryAssign(command))
                .isInstanceOf(SeatAlreadyAssignedException.class);

        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("예약 확정 - 성공 케이스")
    void confirmReservation_Success() {
        // given
        String reservationId = "test-reservation-id";
        String idempotencyKey = "test-idempotency-key";
        ConfirmReservationCommand command = new ConfirmReservationCommand(
                QUEUE_TOKEN, reservationId, idempotencyKey
        );

        when(queuePort.isActive(QUEUE_TOKEN)).thenReturn(true);
        when(queuePort.userIdOf(QUEUE_TOKEN)).thenReturn(USER_ID);

        // restore()로 명시적으로 ID 지정
        Reservation reservation = Reservation.restore(
                new ReservationId(reservationId),
                UserId.ofString(USER_ID),
                new SeatIdentifier(new ConcertScheduleId(SCHEDULE_ID), new SeatNumber(SEAT_NUMBER)),
                Money.of(80_000L),
                ReservationStatus.TEMPORARY_ASSIGNED,
                LocalDateTime.now().minusMinutes(2),
                null,
                0L
        );
        when(reservationRepository.findById(any())).thenReturn(Optional.of(reservation));
        when(paymentUseCase.pay(any())).thenReturn(new PaymentUseCase.BalanceResult(20_000L));

        // when
        ConfirmReservationResult result = reservationService.confirmReservation(command);

        // then
        assertThat(result).isNotNull();
        assertThat(result.remainingBalance()).isEqualTo(20_000L);

        verify(distributedLock).executeWithLock(contains("confirm"), anyLong(), anyInt(), anyLong(), any());
        verify(transactionTemplate).execute(any());

        ArgumentCaptor<PaymentUseCase.PaymentCommand> paymentCaptor =
                ArgumentCaptor.forClass(PaymentUseCase.PaymentCommand.class);
        verify(paymentUseCase).pay(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().amount()).isEqualTo(80_000L);

        verify(reservationRepository).save(any(Reservation.class));
        verify(seatHoldPort).release(any(SeatIdentifier.class));
        verify(queuePort).expire(QUEUE_TOKEN);

        // 검증: 이벤트 발행
        ArgumentCaptor<ReservationConfirmedEvent> eventCaptor =
                ArgumentCaptor.forClass(ReservationConfirmedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        ReservationConfirmedEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.reservationId()).isEqualTo(reservationId);
        assertThat(publishedEvent.userId()).isEqualTo(USER_ID);
        assertThat(publishedEvent.scheduleId()).isEqualTo(SCHEDULE_ID);
        assertThat(publishedEvent.seatNumber()).isEqualTo(SEAT_NUMBER);
        assertThat(publishedEvent.price()).isEqualTo(80_000L);
        assertThat(publishedEvent.confirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("예약 확정 - 잔액 부족으로 실패")
    void confirmReservation_InsufficientBalance() {
        // given
        String reservationId = "test-reservation-id";
        String idempotencyKey = "test-idempotency-key";
        ConfirmReservationCommand command = new ConfirmReservationCommand(
                QUEUE_TOKEN, reservationId, idempotencyKey
        );

        when(queuePort.isActive(QUEUE_TOKEN)).thenReturn(true);
        when(queuePort.userIdOf(QUEUE_TOKEN)).thenReturn(USER_ID);

        // restore()로 명시적으로 ID 지정 (여기도 수정!)
        Reservation reservation = Reservation.restore(
                new ReservationId(reservationId),
                UserId.ofString(USER_ID),
                new SeatIdentifier(new ConcertScheduleId(SCHEDULE_ID), new SeatNumber(SEAT_NUMBER)),
                Money.of(80_000L),
                ReservationStatus.TEMPORARY_ASSIGNED,
                LocalDateTime.now().minusMinutes(2),
                null,
                0L
        );
        when(reservationRepository.findById(any())).thenReturn(Optional.of(reservation));
        when(paymentUseCase.pay(any())).thenThrow(new RuntimeException("잔액이 부족합니다"));

        // when & then
        assertThatThrownBy(() -> reservationService.confirmReservation(command))
                .hasMessage("잔액이 부족합니다");

        verify(distributedLock).executeWithLock(any(), anyLong(), anyInt(), anyLong(), any());
        verify(transactionTemplate).execute(any());
        verify(seatHoldPort, never()).release(any());
        verify(queuePort, never()).expire(any());
        verify(eventPublisher, never()).publishEvent(any(ReservationConfirmedEvent.class));
    }

    @Test
    @DisplayName("예약 조회 - 성공")
    void getReservation_Success() {
        // given
        String reservationId = "test-reservation-id";
        ReservationQuery query = new ReservationQuery(USER_ID, reservationId);

        Reservation reservation = Reservation.temporaryAssign(
                UserId.ofString(USER_ID),
                new SeatIdentifier(new ConcertScheduleId(SCHEDULE_ID), new SeatNumber(SEAT_NUMBER)),
                Money.of(80_000L),
                LocalDateTime.now().minusMinutes(2)
        );
        when(reservationRepository.findById(any())).thenReturn(Optional.of(reservation));

        // when
        ReservationInfo result = reservationService.getReservation(query);

        // then
        assertThat(result).isNotNull();
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.seatNumber()).isEqualTo(SEAT_NUMBER);
        assertThat(result.price()).isEqualTo(80_000L);
        assertThat(result.status()).isEqualTo("TEMPORARY_ASSIGNED");

        verify(distributedLock, never()).executeWithLock(any(), anyLong(), anyInt(), anyLong(), any());
    }

    @Test
    @DisplayName("예약 조회 - 권한 없음")
    void getReservation_Unauthorized() {
        // given
        String reservationId = "test-reservation-id";
        String otherUserId = "550e8400-e29b-41d4-a716-446655440001";
        ReservationQuery query = new ReservationQuery(otherUserId, reservationId);

        Reservation reservation = Reservation.temporaryAssign(
                UserId.ofString(USER_ID),
                new SeatIdentifier(new ConcertScheduleId(SCHEDULE_ID), new SeatNumber(SEAT_NUMBER)),
                Money.of(80_000L),
                LocalDateTime.now().minusMinutes(2)
        );
        when(reservationRepository.findById(any())).thenReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() -> reservationService.getReservation(query))
                .isInstanceOf(UnauthorizedReservationAccessException.class)
                .hasMessage("해당 예약에 접근할 권한이 없습니다");
    }
}