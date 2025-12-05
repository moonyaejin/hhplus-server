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
import lombok.extern.slf4j.Slf4j;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AFTER_COMMIT 동작 검증 통합 테스트
 *
 * 목적:
 * - TransactionalEventListener(AFTER_COMMIT)가 트랜잭션 커밋 후에만 실행되는지 검증
 * - 트랜잭션 실패 시 이벤트가 발행되지 않는지 검증
 *
 * 멘토님 피드백 반영
 * 1. 트랜잭션에 실패하는 경우(커밋이 이루어지지 않는 경우) 이벤트 리스너가 call 되지 않는다는 것을 검증
 * 2. 트랜잭션이 완료되기 전 이벤트 리스너의 call count가 0인것과,
 *    트랜잭션이 완료된 직후 call count가 1인것을 검증
 */
@Disabled("비동기 결제로 변경")
@Slf4j
@SpringBootTest
@DisplayName("AFTER_COMMIT 동작 검증")
class ReservationEventAfterCommitTest {

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

    @Test
    @DisplayName("트랜잭션 실패 시 이벤트가 발행되지 않음 (AFTER_COMMIT 검증)")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void transactionRollback_eventNotPublished() throws InterruptedException {
        // given - 잔액 부족 상황 (결제 실패 -> 트랜잭션 롤백)
        UserWalletJpaEntity wallet = walletJpaRepository.findById(userId).orElseThrow();
        wallet.setBalance(1_000L);  // 80,000원 필요한데 1,000원만 있음
        walletJpaRepository.save(wallet);

        // 이벤트 리스너 호출 횟수 초기화
        reset(rankingUseCase);

        // when - 예약 확정 시도 (실패할 것)
        ReservationUseCase.ConfirmReservationCommand command =
                new ReservationUseCase.ConfirmReservationCommand(
                        queueToken,
                        reservationId,
                        UUID.randomUUID().toString()
                );

        // then - 결제 실패로 예외 발생
        try {
            reservationService.confirmReservation(command);
        } catch (Exception e) {
            log.info("예상된 예외 발생: {}", e.getMessage());
        }

        // 충분한 시간 대기 (이벤트가 발행됐다면 처리될 시간)
        Thread.sleep(3000);

        // 트랜잭션이 롤백되었으므로 이벤트 리스너가 호출되지 않아야 함
        verify(rankingUseCase, never())
                .trackReservation(anyLong(), anyInt());

        // 예약 상태도 변경되지 않아야 함
        Reservation reservation = reservationRepository.findById(new ReservationId(reservationId))
                .orElseThrow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.TEMPORARY_ASSIGNED);

        log.info("검증 완료: 트랜잭션 롤백 시 이벤트 미발행");
    }

    @Disabled("비동기 결제로 변경 - 별도 Kafka 통합 테스트로 대체 예정")
    @Test
    @DisplayName("트랜잭션 커밋 전후 이벤트 리스너 호출 시점 검증")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void eventListener_calledAfterTransactionCommit() throws InterruptedException {
        // given
        reset(rankingUseCase);

        // when
        ReservationUseCase.ConfirmReservationCommand command =
                new ReservationUseCase.ConfirmReservationCommand(
                        queueToken,
                        reservationId,
                        UUID.randomUUID().toString()
                );

        // 트랜잭션 실행 중
        ReservationUseCase.ConfirmReservationResult result =
                reservationService.confirmReservation(command);

        // 트랜잭션은 완료되었지만 비동기 이벤트는 아직 처리 중일 수 있음
        // 짧은 시간 대기 후 확인
        Thread.sleep(100);

        // 비동기 처리 완료 대기
        Thread.sleep(3000);

        // then - 트랜잭션 커밋 후 이벤트 리스너가 호출됨
        verify(rankingUseCase, times(1))
                .trackReservation(eq(scheduleId), eq(1));

        // 예약 상태도 확정되어 있어야 함
        Reservation confirmedReservation = reservationRepository.findById(new ReservationId(reservationId))
                .orElseThrow();
        assertThat(confirmedReservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(result.remainingBalance()).isEqualTo(120_000L);

        log.info("검증 완료: 트랜잭션 커밋 후 이벤트 발행");
    }

    @Disabled("비동기 결제로 변경 - 별도 Kafka 통합 테스트로 대체 예정")
    @Test
    @DisplayName("예약 취소 시 랭킹 차감 이벤트 발행")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cancelReservation_triggersDecrementEvent() throws InterruptedException {
        // given
        // 먼저 예약을 확정 상태로 만들기
        Reservation reservation = reservationRepository.findById(new ReservationId(reservationId))
                .orElseThrow();
        reservation.confirm(LocalDateTime.now());
        reservationRepository.save(reservation);

        reset(rankingUseCase);  // Mock 초기화

        // when - 예약 취소
        ReservationUseCase.CancelReservationCommand command =
                new ReservationUseCase.CancelReservationCommand(
                        null,                    // queueToken (취소는 대기열 검증 불필요)
                        reservationId,           // reservationId
                        userId.toString(),       // userId
                        "테스트 취소"             // reason
                );

        reservationService.cancelReservation(command);

        // 비동기 이벤트 처리 완료 대기
        Thread.sleep(3000);

        // then - decrementReservation 호출 확인
        verify(rankingUseCase, times(1))
                .decrementReservation(eq(scheduleId), eq(1));

        // 예약 상태도 CANCELLED로 변경되어야 함
        Reservation cancelledReservation = reservationRepository.findById(new ReservationId(reservationId))
                .orElseThrow();
        assertThat(cancelledReservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);

        log.info("검증 완료: 예약 취소 시 랭킹 차감 이벤트 정상 발행");
    }

    @Disabled("비동기 결제로 변경 - 별도 Kafka 통합 테스트로 대체 예정")
    @Test
    @DisplayName("예약 취소 이벤트 리스너 예외 발생 시에도 취소는 성공")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cancelEventListenerException_doesNotAffectCancellation() throws InterruptedException {
        // given
        Reservation reservation = reservationRepository.findById(new ReservationId(reservationId))
                .orElseThrow();
        reservation.confirm(LocalDateTime.now());
        reservationRepository.save(reservation);

        reset(rankingUseCase);

        // 랭킹 서비스가 예외를 던지도록 설정
        doThrow(new RuntimeException("랭킹 업데이트 실패"))
                .when(rankingUseCase).decrementReservation(anyLong(), anyInt());

        // when - 예약 취소 (이벤트 리스너에서 예외 발생)
        ReservationUseCase.CancelReservationCommand command =
                new ReservationUseCase.CancelReservationCommand(
                        null,                    // queueToken
                        reservationId,           // reservationId
                        userId.toString(),       // userId
                        "이벤트 실패 테스트"      // reason
                );

        ReservationUseCase.CancelReservationResult result =
                reservationService.cancelReservation(command);

        // 비동기 이벤트 처리 시도 대기
        Thread.sleep(3000);

        // then - 예약 취소는 성공해야 함
        assertThat(result).isNotNull();

        Reservation cancelledReservation = reservationRepository.findById(new ReservationId(reservationId))
                .orElseThrow();
        assertThat(cancelledReservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);

        // 이벤트 리스너는 호출되었지만 예외로 인해 실패
        verify(rankingUseCase, times(1))
                .decrementReservation(anyLong(), anyInt());

        log.info("검증 완료: 이벤트 리스너 예외가 핵심 비즈니스에 영향 없음");
    }
}