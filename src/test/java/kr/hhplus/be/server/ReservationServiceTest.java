package kr.hhplus.be.server;

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
import kr.hhplus.be.server.infrastructure.kafka.message.PaymentRequestMessage;
import kr.hhplus.be.server.infrastructure.kafka.producer.PaymentKafkaProducer;
import kr.hhplus.be.server.infrastructure.redis.lock.RedisDistributedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
    @Mock private PaymentKafkaProducer paymentKafkaProducer;

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
                eventPublisher,
                paymentKafkaProducer
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

        // Mock 설정
        when(queuePort.isActive(QUEUE_TOKEN)).thenReturn(true);
        when(queuePort.userIdOf(QUEUE_TOKEN)).thenReturn(USER_ID);

        ConcertSchedule schedule = new ConcertSchedule(
                new ConcertScheduleId(SCHEDULE_ID),
                new ConcertId(1L),
                LocalDate.now(),
                50
        );
        when(concertSchedulePort.findById(any())).thenReturn(Optional.of(schedule));

        // Redis 좌석 점유 성공
        when(seatHoldPort.tryHold(any(), any(), any())).thenReturn(true);

        // 도메인 서비스 Mock
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

        // 검증: 분산락 사용 확인
        verify(distributedLock).executeWithLock(
                anyString(),  // lockKey
                anyLong(),    // ttl
                anyInt(),     // retryCount
                anyLong(),    // retryDelay
                any()         // action
        );

        // 검증: 트랜잭션 사용 확인
        verify(transactionTemplate).execute(any());

        // 검증: Redis 좌석 점유 시도
        verify(seatHoldPort).tryHold(any(SeatIdentifier.class), any(UserId.class), eq(Duration.ofMinutes(5)));

        // 검증: 예약 저장
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

        // 검증: 분산락도 시도하지 않음 (검증 단계에서 실패)
        verify(distributedLock, never()).executeWithLock(any(), anyLong(), anyInt(), anyLong(), any());

        // 검증: 좌석 점유 시도하지 않음
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

        // Redis 좌석 점유 실패
        when(seatHoldPort.tryHold(any(), any(), any())).thenReturn(false);

        // 다른 사용자가 점유 중
        SeatHoldStatus otherUserHold = new SeatHoldStatus(
                UserId.generate(),
                LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusMinutes(4)
        );
        when(seatHoldPort.getHoldStatus(any())).thenReturn(otherUserHold);

        // when & then
        assertThatThrownBy(() -> reservationService.temporaryAssign(command))
                .isInstanceOf(SeatAlreadyAssignedException.class);

        // 검증: 예약 저장하지 않음
        verify(reservationRepository, never()).save(any());
    }

    @Test
    @DisplayName("예약 확정 (비동기 결제) - 성공 케이스")
    void confirmReservation_Success() {
        // given
        String reservationId = "test-reservation-id";
        String idempotencyKey = "test-idempotency-key";
        ConfirmReservationCommand command = new ConfirmReservationCommand(
                QUEUE_TOKEN, reservationId, idempotencyKey
        );

        // Mock 설정
        when(queuePort.isActive(QUEUE_TOKEN)).thenReturn(true);
        when(queuePort.userIdOf(QUEUE_TOKEN)).thenReturn(USER_ID);

        // restore()로 특정 ID를 가진 예약 생성
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
        when(reservationRepository.save(any())).thenReturn(reservation);

        // when
        ConfirmReservationResult result = reservationService.confirmReservation(command);

        // then - 비동기 결제이므로 PAYMENT_PENDING 상태로 즉시 응답
        assertThat(result).isNotNull();
        assertThat(result.reservationId()).isEqualTo(reservationId);
        assertThat(result.status()).isEqualTo("PAYMENT_PENDING");
        assertThat(result.remainingBalance()).isNull();  // 비동기라 아직 모름
        assertThat(result.confirmedAt()).isNull();       // 비동기라 아직 확정 안됨

        // 검증: 분산락 사용 확인
        verify(distributedLock).executeWithLock(
                contains("confirm"),  // lockKey에 "confirm" 포함
                anyLong(),
                anyInt(),
                anyLong(),
                any()
        );

        // 검증: 트랜잭션 사용 확인
        verify(transactionTemplate).execute(any());

        // 검증: PaymentUseCase.pay()는 호출하지 않음 (비동기!)
        verify(paymentUseCase, never()).pay(any());

        // 검증: Kafka로 결제 요청 발행
        ArgumentCaptor<PaymentRequestMessage> kafkaCaptor =
                ArgumentCaptor.forClass(PaymentRequestMessage.class);
        verify(paymentKafkaProducer).sendPaymentRequest(kafkaCaptor.capture());

        PaymentRequestMessage sentMessage = kafkaCaptor.getValue();
        assertThat(sentMessage.reservationId()).isEqualTo(reservationId);
        assertThat(sentMessage.userId()).isEqualTo(USER_ID);
        assertThat(sentMessage.amount()).isEqualTo(80_000L);
        assertThat(sentMessage.idempotencyKey()).isEqualTo(idempotencyKey);

        // 검증: 예약 저장 (상태 변경: PAYMENT_PENDING)
        verify(reservationRepository).save(any(Reservation.class));

        // 검증: 토큰 만료
        verify(queuePort).expire(QUEUE_TOKEN);

        // 검증: Redis 좌석 해제는 하지 않음 (결제 완료 후 PaymentResultConsumer에서 처리)
        verify(seatHoldPort, never()).release(any());
    }

    @Disabled("비동기 결제로 변경")
    @Test
    @DisplayName("예약 확정 - 만료된 예약")
    void confirmReservation_ExpiredReservation() {
        // given
        String reservationId = "test-reservation-id";
        String idempotencyKey = "test-idempotency-key";
        ConfirmReservationCommand command = new ConfirmReservationCommand(
                QUEUE_TOKEN, reservationId, idempotencyKey
        );

        when(queuePort.isActive(QUEUE_TOKEN)).thenReturn(true);
        when(queuePort.userIdOf(QUEUE_TOKEN)).thenReturn(USER_ID);

        // 6분 전에 임시 배정된 예약 (5분 만료) - restore() 사용
        Reservation expiredReservation = Reservation.restore(
                new ReservationId(reservationId),
                UserId.ofString(USER_ID),
                new SeatIdentifier(new ConcertScheduleId(SCHEDULE_ID), new SeatNumber(SEAT_NUMBER)),
                Money.of(80_000L),
                ReservationStatus.TEMPORARY_ASSIGNED,
                LocalDateTime.now().minusMinutes(6),
                null,
                0L
        );
        when(reservationRepository.findById(any())).thenReturn(Optional.of(expiredReservation));

        // 도메인 서비스에서 만료 검증 실패
        doThrow(new IllegalStateException("만료된 예약입니다"))
                .when(domainService).validateConfirmation(any(), any());

        // when & then
        assertThatThrownBy(() -> reservationService.confirmReservation(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("만료된 예약입니다");

        // 검증: Kafka 발행하지 않음
        verify(paymentKafkaProducer, never()).sendPaymentRequest(any());

        // 검증: 토큰 만료되지 않음
        verify(queuePort, never()).expire(any());
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

        // 검증: 조회는 분산락 사용하지 않음
        verify(distributedLock, never()).executeWithLock(any(), anyLong(), anyInt(), anyLong(), any());
    }

    @Test
    @DisplayName("예약 조회 - 권한 없음")
    void getReservation_Unauthorized() {
        // given
        String reservationId = "test-reservation-id";
        String otherUserId = "550e8400-e29b-41d4-a716-446655440001";  // 다른 사용자 ID
        ReservationQuery query = new ReservationQuery(otherUserId, reservationId);

        Reservation reservation = Reservation.temporaryAssign(
                UserId.ofString(USER_ID),  // 원래 사용자의 예약
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

    @Test
    @DisplayName("예약 확정 결과가 PAYMENT_PENDING인지 확인")
    void confirmReservation_ReturnsPendingStatus() {
        // given
        String reservationId = "test-reservation-id";
        ConfirmReservationCommand command = new ConfirmReservationCommand(
                QUEUE_TOKEN, reservationId, "idempotency-key"
        );

        when(queuePort.isActive(QUEUE_TOKEN)).thenReturn(true);
        when(queuePort.userIdOf(QUEUE_TOKEN)).thenReturn(USER_ID);

        // restore()로 특정 ID를 가진 예약 생성
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
        when(reservationRepository.save(any())).thenReturn(reservation);

        // when
        ConfirmReservationResult result = reservationService.confirmReservation(command);

        // then
        assertThat(result.isPaymentPending()).isTrue();
    }
}