package kr.hhplus.be.server.event;

import kr.hhplus.be.server.application.port.in.QueueUseCase;
import kr.hhplus.be.server.application.port.in.RankingUseCase;
import kr.hhplus.be.server.application.port.in.ReservationUseCase;
import kr.hhplus.be.server.application.service.ReservationService;
import kr.hhplus.be.server.domain.common.Money;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import kr.hhplus.be.server.domain.reservation.*;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertScheduleJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertScheduleJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWalletJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.user.jpa.entity.UserJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.user.jpa.repository.UserJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 예약 확정 이벤트 통합 테스트
 */
@SpringBootTest
@DisplayName("예약 확정 이벤트 통합 테스트")
class ReservationEventIntegrationTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private UserWalletJpaRepository walletJpaRepository;

    @Autowired
    private ConcertJpaRepository concertJpaRepository;

    @Autowired
    private ConcertScheduleJpaRepository scheduleJpaRepository;

    @Autowired
    private QueueUseCase queueUseCase;

    @MockitoSpyBean
    private RankingUseCase rankingUseCase;

    private UUID userId;
    private String queueToken;
    private Long scheduleId;
    private String reservationId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        UserJpaEntity user = new UserJpaEntity(userId, "테스트사용자_" + System.currentTimeMillis());
        userJpaRepository.save(user);

        UserWalletJpaEntity wallet = new UserWalletJpaEntity(userId, 200_000L);
        walletJpaRepository.save(wallet);

        ConcertJpaEntity concert = new ConcertJpaEntity("테스트 콘서트");
        concert = concertJpaRepository.save(concert);

        ConcertScheduleJpaEntity schedule = new ConcertScheduleJpaEntity(
                concert,
                LocalDate.now().plusDays(30),
                50
        );
        schedule = scheduleJpaRepository.save(schedule);
        scheduleId = schedule.getId();

        QueueUseCase.IssueTokenCommand tokenCommand =
                new QueueUseCase.IssueTokenCommand(userId.toString());
        QueueUseCase.TokenInfo tokenInfo = queueUseCase.issueToken(tokenCommand);
        queueToken = tokenInfo.token();

        reservationId = UUID.randomUUID().toString();
        Reservation reservation = Reservation.restore(
                new ReservationId(reservationId),
                UserId.of(userId),
                new SeatIdentifier(
                        new ConcertScheduleId(scheduleId),
                        new SeatNumber(10)
                ),
                Money.of(80_000L),
                ReservationStatus.TEMPORARY_ASSIGNED,
                LocalDateTime.now().minusMinutes(2),
                null,
                0L
        );
        reservationRepository.save(reservation);
    }

    @Disabled("비동기 결제로 변경 - 별도 Kafka 통합 테스트로 대체 예정")
    @Test
    @DisplayName("예약 확정 성공 시 이벤트가 발행되고 리스너가 실행됨")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void confirmReservation_success_triggersEvent() throws InterruptedException {
        ReservationUseCase.ConfirmReservationCommand command =
                new ReservationUseCase.ConfirmReservationCommand(
                        queueToken,
                        reservationId,
                        UUID.randomUUID().toString()
                );

        ReservationUseCase.ConfirmReservationResult result =
                reservationService.confirmReservation(command);

        Thread.sleep(3000);

        assertThat(result).isNotNull();
        assertThat(result.remainingBalance()).isEqualTo(120_000L);

        verify(rankingUseCase, timeout(5000).times(1))
                .trackReservation(eq(scheduleId), eq(1));

        Reservation confirmedReservation = reservationRepository.findById(new ReservationId(reservationId))
                .orElseThrow();
        assertThat(confirmedReservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Disabled("비동기 결제로 변경 - 별도 Kafka 통합 테스트로 대체 예정")
    @Test
    @DisplayName("여러 예약 확정 시 모든 이벤트가 처리됨")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void multipleReservations_allEventsProcessed() throws InterruptedException {
        int additionalReservations = 2;
        for (int i = 1; i <= additionalReservations; i++) {
            String newReservationId = UUID.randomUUID().toString();
            Reservation reservation = Reservation.restore(
                    new ReservationId(newReservationId),
                    UserId.of(userId),
                    new SeatIdentifier(
                            new ConcertScheduleId(scheduleId),
                            new SeatNumber(10 + i)
                    ),
                    Money.of(80_000L),
                    ReservationStatus.TEMPORARY_ASSIGNED,
                    LocalDateTime.now().minusMinutes(2),
                    null,
                    0L
            );
            reservationRepository.save(reservation);

            UserWalletJpaEntity wallet = walletJpaRepository.findById(userId)
                    .orElseThrow();
            wallet.setBalance(wallet.getBalance() + 80_000L);
            walletJpaRepository.save(wallet);
        }

        ReservationUseCase.ConfirmReservationCommand command =
                new ReservationUseCase.ConfirmReservationCommand(
                        queueToken,
                        reservationId,
                        UUID.randomUUID().toString()
                );

        reservationService.confirmReservation(command);

        Thread.sleep(3000);

        verify(rankingUseCase, times(1))
                .trackReservation(eq(scheduleId), eq(1));
    }

    @Disabled("비동기 결제로 변경 - 별도 Kafka 통합 테스트로 대체 예정")
    @Test
    @DisplayName("이벤트 리스너 예외 발생 시에도 예약 확정은 성공")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void eventListenerException_doesNotAffectReservation() throws InterruptedException {
        doThrow(new RuntimeException("Redis 연결 실패"))
                .when(rankingUseCase)
                .trackReservation(anyLong(), anyInt());

        ReservationUseCase.ConfirmReservationCommand command =
                new ReservationUseCase.ConfirmReservationCommand(
                        queueToken,
                        reservationId,
                        UUID.randomUUID().toString()
                );

        ReservationUseCase.ConfirmReservationResult result =
                reservationService.confirmReservation(command);

        Thread.sleep(3000);

        assertThat(result).isNotNull();
        assertThat(result.remainingBalance()).isEqualTo(120_000L);

        verify(rankingUseCase, times(1))
                .trackReservation(anyLong(), anyInt());

        Reservation confirmedReservation = reservationRepository.findById(new ReservationId(reservationId))
                .orElseThrow();
        assertThat(confirmedReservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }
}