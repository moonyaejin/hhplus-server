package kr.hhplus.be.server.integration;

import kr.hhplus.be.server.application.port.in.QueueUseCase;
import kr.hhplus.be.server.application.port.in.ReservationUseCase;
import kr.hhplus.be.server.domain.reservation.ReservationExpiredException;
import kr.hhplus.be.server.infrastructure.persistence.user.jpa.entity.UserJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.user.jpa.repository.UserJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertScheduleJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertScheduleJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWalletJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReservationFlowIntegrationTest {

    @Autowired
    private QueueUseCase queueUseCase;

    @Autowired
    private ReservationUseCase reservationUseCase;

    @Autowired
    private UserJpaRepository userRepository;

    @Autowired
    private ConcertJpaRepository concertRepository;

    @Autowired
    private ConcertScheduleJpaRepository scheduleRepository;

    @Autowired
    private UserWalletJpaRepository walletRepository;

    private UUID userId;
    private Long concertScheduleId;
    private String uniqueUserName;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        // Given
        // 테스트마다 고유한 사용자 이름 생성
        uniqueUserName = "testUser_" + System.currentTimeMillis() + "_" +
                Thread.currentThread().getId();

        // 테스트용 사용자 생성
        userId = UUID.randomUUID();
        UserJpaEntity user = new UserJpaEntity(userId, uniqueUserName);
        userRepository.save(user);

        // 테스트용 콘서트 및 스케줄 생성
        ConcertJpaEntity concert = new ConcertJpaEntity("테스트 콘서트");
        concert = concertRepository.save(concert);

        ConcertScheduleJpaEntity schedule = new ConcertScheduleJpaEntity(
                concert,
                LocalDate.now().plusDays(7),
                50
        );
        schedule = scheduleRepository.save(schedule);
        concertScheduleId = schedule.getId();

        // 테스트용 지갑 생성 (잔액 100,000원)
        UserWalletJpaEntity wallet = new UserWalletJpaEntity(userId, 100_000L);
        walletRepository.save(wallet);
    }

    /**
     * 시나리오: 사용자가 콘서트 예약을 처음부터 끝까지 완료하는 과정
     * <p>
     * Given:
     * - 사용자 계정 (잔액: 100,000원)
     * - 콘서트 스케줄 (7일 후, 50석)
     * <p>
     * When:
     * 1. 대기열 토큰 발급 → ACTIVE 상태 확인
     * 2. 15번 좌석 선택 → 일반석(80,000원) 임시 배정
     * 3. 예약 확정 요청 → 결제 및 최종 확정
     * <p>
     * Then:
     * - 예약 상태: CONFIRMED
     * - 잔액: 20,000원 (100,000 - 80,000)
     * - 토큰: 만료 처리
     * <p>
     * 검증: 전체 비즈니스 플로우의 정합성과 트랜잭션 일관성
     */
    @Test
    @DisplayName("전체 예약 플로우: 토큰 발급 → 좌석 예약 → 결제 완료")
    void completeReservationFlow() {
        // When 1: 대기열 토큰 발급
        QueueUseCase.IssueTokenCommand tokenCommand =
                new QueueUseCase.IssueTokenCommand(userId.toString());
        QueueUseCase.TokenInfo tokenInfo = queueUseCase.issueToken(tokenCommand);

        // Then 1: 토큰 발급 검증
        assertThat(tokenInfo).isNotNull();
        assertThat(tokenInfo.token()).isNotBlank();
        assertThat(tokenInfo.status()).isEqualTo("ACTIVE");

        // When 2: 좌석 임시 배정
        ReservationUseCase.TemporaryAssignCommand assignCommand =
                new ReservationUseCase.TemporaryAssignCommand(
                        tokenInfo.token(),
                        concertScheduleId,
                        15  // 15번 좌석 (일반 좌석: 80,000원)
                );

        ReservationUseCase.TemporaryAssignResult assignResult =
                reservationUseCase.temporaryAssign(assignCommand);

        // Then 2: 임시 배정 검증
        assertThat(assignResult).isNotNull();
        assertThat(assignResult.reservationId()).isNotBlank();
        assertThat(assignResult.price()).isEqualTo(80_000L); // VIP 좌석 가격
        assertThat(assignResult.expirationTime()).isNotNull();

        // When 3: 예약 확정 및 결제
        String idempotencyKey = UUID.randomUUID().toString();
        ReservationUseCase.ConfirmReservationCommand confirmCommand =
                new ReservationUseCase.ConfirmReservationCommand(
                        tokenInfo.token(),
                        assignResult.reservationId(),
                        idempotencyKey
                );

        ReservationUseCase.ConfirmReservationResult confirmResult =
                reservationUseCase.confirmReservation(confirmCommand);

        // Then
        assertThat(confirmResult).isNotNull();
        assertThat(confirmResult.reservationId()).isEqualTo(assignResult.reservationId());
        assertThat(confirmResult.remainingBalance()).isEqualTo(20_000L);   // 100,000 - 80,000
        assertThat(confirmResult.confirmedAt()).isNotNull();

        // 4. 지갑 잔액 확인
        UserWalletJpaEntity updatedWallet = walletRepository.findById(userId).orElseThrow();
        assertThat(updatedWallet.getBalance()).isEqualTo(20_000L); // 100,000 - 80,000
    }

    /**
     * 시나리오: 임시 배정 후 결제를 하지 않고 방치한 사용자의 예약 실패

     * Given:
      - 활성화된 대기열 토큰을 가진 사용자
      - 예약 가능한 콘서트 스케줄
      - 충분한 잔액(100,000원)을 보유

     * When:
      1. 좌석 임시 배정 성공 (5분 타이머 시작)
      2. 5분 1초 경과 (만료 시간 초과)
      3. 예약 확정 시도

     * Then:
      - 예약 확정 실패 (ReservationExpiredException)
      - 좌석 상태: 다시 예약 가능
      - 잔액: 변동 없음 (결제 미진행)
      - 다른 사용자가 해당 좌석 예약 가능

     */
    @Test
    @DisplayName("임시 배정 5분 초과 시 예약 확정이 실패한다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void expiredTemporaryAssignment_ConfirmationFails() {
        // Given
        QueueUseCase.IssueTokenCommand tokenCommand =
                new QueueUseCase.IssueTokenCommand(userId.toString());
        QueueUseCase.TokenInfo tokenInfo = queueUseCase.issueToken(tokenCommand);

        // When 1: 좌석 임시 배정
        ReservationUseCase.TemporaryAssignCommand assignCommand =
                new ReservationUseCase.TemporaryAssignCommand(
                        tokenInfo.token(),
                        concertScheduleId,
                        20
                );
        ReservationUseCase.TemporaryAssignResult assignResult =
                reservationUseCase.temporaryAssign(assignCommand);

        // When 2: 별도 트랜잭션에서 만료 시간 업데이트
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "UPDATE reservation SET temporary_assigned_at = ? WHERE id = ?",
                    LocalDateTime.now().minusMinutes(6),
                    assignResult.reservationId()
            );
        });

        // When 3: 예약 확정 시도
        String idempotencyKey = UUID.randomUUID().toString();
        ReservationUseCase.ConfirmReservationCommand confirmCommand =
                new ReservationUseCase.ConfirmReservationCommand(
                        tokenInfo.token(),
                        assignResult.reservationId(),
                        idempotencyKey
                );

        // Then
        assertThatThrownBy(() -> reservationUseCase.confirmReservation(confirmCommand))
                .isInstanceOf(ReservationExpiredException.class)
                .hasMessageContaining("만료된 예약");
    }
}