package kr.hhplus.be.server.integration.kafka;

import kr.hhplus.be.server.application.port.in.QueueUseCase;
import kr.hhplus.be.server.application.port.in.ReservationUseCase;
import kr.hhplus.be.server.domain.reservation.Reservation;
import kr.hhplus.be.server.domain.reservation.ReservationId;
import kr.hhplus.be.server.domain.reservation.ReservationRepository;
import kr.hhplus.be.server.domain.reservation.ReservationStatus;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertScheduleJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertScheduleJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWalletJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.ReservationJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.SeatHoldJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.user.jpa.entity.UserJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.user.jpa.repository.UserJpaRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kafka 비동기 결제 통합 테스트
 */
@SpringBootTest
@ActiveProfiles("local-test")
@DisplayName("Kafka 비동기 결제 통합 테스트")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentKafkaIntegrationTest {

    @Autowired
    private QueueUseCase queueUseCase;

    @Autowired
    private ReservationUseCase reservationUseCase;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationJpaRepository reservationJpaRepository;

    @Autowired
    private SeatHoldJpaRepository seatHoldJpaRepository;

    @Autowired
    private UserJpaRepository userRepository;

    @Autowired
    private ConcertJpaRepository concertRepository;

    @Autowired
    private ConcertScheduleJpaRepository scheduleRepository;

    @Autowired
    private UserWalletJpaRepository walletRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private UUID userId;
    private Long concertScheduleId;

    @BeforeEach
    void setUp() {
        // Redis 좌석 점유 정리
        redisTemplate.keys("seat:hold:*").forEach(redisTemplate::delete);
        redisTemplate.keys("lock:*").forEach(redisTemplate::delete);

        // 테스트용 사용자
        userId = UUID.randomUUID();
        UserJpaEntity user = new UserJpaEntity(userId, "testUser_" + System.currentTimeMillis());
        userRepository.save(user);

        // 테스트용 콘서트 & 스케줄
        ConcertJpaEntity concert = new ConcertJpaEntity("테스트 콘서트");
        concert = concertRepository.save(concert);

        ConcertScheduleJpaEntity schedule = new ConcertScheduleJpaEntity(
                concert,
                LocalDate.now().plusDays(7),
                50
        );
        schedule = scheduleRepository.save(schedule);
        concertScheduleId = schedule.getId();

        // 테스트용 지갑 (잔액 100,000원)
        UserWalletJpaEntity wallet = new UserWalletJpaEntity(userId, 100_000L);
        walletRepository.save(wallet);
    }

    @Nested
    @DisplayName("비동기 결제 전체 흐름")
    class FullAsyncPaymentFlow {

        @Test
        @Order(1)
        @DisplayName("예약 확정 → Kafka 결제 요청 → 결제 처리 → 예약 CONFIRMED")
        void shouldCompleteFullPaymentFlowViaKafka() {
            // Given: 토큰 발급
            QueueUseCase.TokenInfo tokenInfo = queueUseCase.issueToken(
                    new QueueUseCase.IssueTokenCommand(userId.toString()));

            assertThat(tokenInfo.status()).isEqualTo("ACTIVE");

            // Given: 좌석 임시 배정
            ReservationUseCase.TemporaryAssignResult assignResult =
                    reservationUseCase.temporaryAssign(new ReservationUseCase.TemporaryAssignCommand(
                            tokenInfo.token(),
                            concertScheduleId,
                            15
                    ));

            assertThat(assignResult.reservationId()).isNotBlank();

            // When: 예약 확정 요청
            ReservationUseCase.ConfirmReservationResult confirmResult =
                    reservationUseCase.confirmReservation(new ReservationUseCase.ConfirmReservationCommand(
                            tokenInfo.token(),
                            assignResult.reservationId(),
                            UUID.randomUUID().toString()
                    ));

            // Then: 즉시 응답은 PAYMENT_PENDING
            assertThat(confirmResult.status()).isEqualTo("PAYMENT_PENDING");

            // Then: 비동기로 CONFIRMED 상태가 됨
            await()
                    .atMost(10, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        Reservation updated = reservationRepository
                                .findById(new ReservationId(assignResult.reservationId()))
                                .orElseThrow();
                        assertThat(updated.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
                    });

            // Then: 잔액 확인
            UserWalletJpaEntity wallet = walletRepository.findById(userId).orElseThrow();
            assertThat(wallet.getBalance()).isEqualTo(20_000L);
        }

        @Test
        @Order(2)
        @DisplayName("잔액 부족 시 예약이 PAYMENT_FAILED 상태가 된다")
        void shouldFailWhenInsufficientBalance() {
            // Given: 잔액 부족
            walletRepository.deleteById(userId);
            walletRepository.save(new UserWalletJpaEntity(userId, 10_000L));

            QueueUseCase.TokenInfo tokenInfo = queueUseCase.issueToken(
                    new QueueUseCase.IssueTokenCommand(userId.toString()));

            ReservationUseCase.TemporaryAssignResult assignResult =
                    reservationUseCase.temporaryAssign(new ReservationUseCase.TemporaryAssignCommand(
                            tokenInfo.token(),
                            concertScheduleId,
                            16
                    ));

            // When
            reservationUseCase.confirmReservation(new ReservationUseCase.ConfirmReservationCommand(
                    tokenInfo.token(),
                    assignResult.reservationId(),
                    UUID.randomUUID().toString()
            ));

            // Then
            await()
                    .atMost(10, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        Reservation updated = reservationRepository
                                .findById(new ReservationId(assignResult.reservationId()))
                                .orElseThrow();
                        assertThat(updated.getStatus()).isEqualTo(ReservationStatus.PAYMENT_FAILED);
                    });
        }
    }

    @Nested
    @DisplayName("상태 변화 검증")
    class StatusTransition {

        @Test
        @DisplayName("TEMPORARY_ASSIGNED → PAYMENT_PENDING → CONFIRMED")
        void shouldTransitionCorrectly() {
            // Given
            QueueUseCase.TokenInfo tokenInfo = queueUseCase.issueToken(
                    new QueueUseCase.IssueTokenCommand(userId.toString()));

            ReservationUseCase.TemporaryAssignResult assignResult =
                    reservationUseCase.temporaryAssign(new ReservationUseCase.TemporaryAssignCommand(
                            tokenInfo.token(),
                            concertScheduleId,
                            17
                    ));

            // Then 1: 임시 배정 상태
            Reservation afterAssign = reservationRepository
                    .findById(new ReservationId(assignResult.reservationId()))
                    .orElseThrow();
            assertThat(afterAssign.getStatus()).isEqualTo(ReservationStatus.TEMPORARY_ASSIGNED);

            // When: 확정 요청
            reservationUseCase.confirmReservation(new ReservationUseCase.ConfirmReservationCommand(
                    tokenInfo.token(),
                    assignResult.reservationId(),
                    UUID.randomUUID().toString()
            ));

            // Then 2: PAYMENT_PENDING
            Reservation afterRequest = reservationRepository
                    .findById(new ReservationId(assignResult.reservationId()))
                    .orElseThrow();
            assertThat(afterRequest.getStatus()).isEqualTo(ReservationStatus.PAYMENT_PENDING);

            // Then 3: 최종 CONFIRMED
            await()
                    .atMost(10, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .untilAsserted(() -> {
                        Reservation finalState = reservationRepository
                                .findById(new ReservationId(assignResult.reservationId()))
                                .orElseThrow();
                        assertThat(finalState.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
                    });
        }
    }

    @Nested
    @DisplayName("여러 예약 처리")
    class MultipleReservations {

        @Test
        @DisplayName("여러 사용자의 예약이 각각 정상 처리된다")
        void shouldProcessMultipleReservations() {
            // Given
            int userCount = 3;
            String[] reservationIds = new String[userCount];

            for (int i = 0; i < userCount; i++) {
                UUID newUserId = UUID.randomUUID();
                userRepository.save(new UserJpaEntity(newUserId, "user_" + i + "_" + System.currentTimeMillis()));
                walletRepository.save(new UserWalletJpaEntity(newUserId, 100_000L));

                QueueUseCase.TokenInfo tokenInfo = queueUseCase.issueToken(
                        new QueueUseCase.IssueTokenCommand(newUserId.toString()));

                ReservationUseCase.TemporaryAssignResult assignResult =
                        reservationUseCase.temporaryAssign(new ReservationUseCase.TemporaryAssignCommand(
                                tokenInfo.token(),
                                concertScheduleId,
                                30 + i
                        ));

                reservationIds[i] = assignResult.reservationId();

                reservationUseCase.confirmReservation(new ReservationUseCase.ConfirmReservationCommand(
                        tokenInfo.token(),
                        assignResult.reservationId(),
                        UUID.randomUUID().toString()
                ));
            }

            // Then
            await()
                    .atMost(30, TimeUnit.SECONDS)
                    .pollInterval(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        for (String reservationId : reservationIds) {
                            Reservation reservation = reservationRepository
                                    .findById(new ReservationId(reservationId))
                                    .orElseThrow();
                            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
                        }
                    });
        }
    }
}