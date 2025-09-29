package kr.hhplus.be.server.concurrency;

import kr.hhplus.be.server.application.port.in.PaymentUseCase;
import kr.hhplus.be.server.application.port.in.QueueUseCase;
import kr.hhplus.be.server.application.port.in.ReservationUseCase;
import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import kr.hhplus.be.server.domain.reservation.SeatIdentifier;
import kr.hhplus.be.server.domain.reservation.SeatNumber;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertScheduleJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertScheduleJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWalletJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.queue.jpa.repository.QueueTokenJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.SeatHoldJpaRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import static org.junit.jupiter.api.Assertions.fail;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 극한 동시성 시나리오 테스트
 * - 대량 트래픽 상황 검증
 * - 복합 작업 플로우 검증
 * - 시스템 안정성 한계 테스트
 */
@Slf4j
@SpringBootTest
@TestPropertySource(properties = {
        "app.payment.use-conditional-update=true"  // 개선된 방식으로 테스트
})
@DisplayName("[극한 시나리오] 동시성 스트레스 테스트")
class ExtremeConcurrencyTest {

    @Autowired
    private SeatHoldPort seatHoldPort;

    @Autowired
    private PaymentUseCase paymentUseCase;

    @Autowired
    private QueueUseCase queueUseCase;

    @Autowired
    private ReservationUseCase reservationUseCase;

    @Autowired
    private UserWalletJpaRepository walletRepository;

    @Autowired
    private SeatHoldJpaRepository seatHoldRepository;

    @Autowired
    private ConcertJpaRepository concertRepository;

    @Autowired
    private ConcertScheduleJpaRepository scheduleRepository;

    private Long scheduleId;

    @Autowired
    private QueueTokenJpaRepository queueTokenJpaRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        // 1. 모든 좌석 점유 삭제
        seatHoldRepository.deleteAll();
        seatHoldRepository.flush();
        System.out.println("좌석 점유 정리");

        // 2. 예약 삭제 (필요시)
        // reservationRepository.deleteAll();

        // 3. 대기열 정리 (가장 중요!)
        queueTokenJpaRepository.deleteAll();
        queueTokenJpaRepository.flush();
        System.out.println("대기열 정리");

        // 4. 지갑 정리
        walletRepository.deleteAll();
        walletRepository.flush();
        System.out.println("지갑 정리");

        // 5. 테스트용 데이터 생성
        ConcertJpaEntity concert = new ConcertJpaEntity("극한테스트 콘서트");
        concert = concertRepository.save(concert);
        System.out.println("콘서트 생성: id=" + concert.getId());

        ConcertScheduleJpaEntity schedule = new ConcertScheduleJpaEntity(
                concert,
                LocalDate.now().plusDays(7),
                50
        );
        schedule = scheduleRepository.save(schedule);
        scheduleId = schedule.getId();
        System.out.println("스케줄 생성: id=" + scheduleId);
    }

    @Test
    @DisplayName("시나리오 1: 1000명이 1개 좌석 동시 예약")
    void extreme_1000_users_for_one_seat() throws InterruptedException {
        // Given
        SeatIdentifier seat = new SeatIdentifier(
                new ConcertScheduleId(scheduleId),
                new SeatNumber(1)
        );

        int threadCount = 1000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(50);

        long startTime = System.currentTimeMillis();

        // When
        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                try {
                    startLatch.await();

                    String userId = UUID.randomUUID().toString();
                    boolean success = seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(userId),
                            Duration.ofMinutes(5)
                    );

                    if (success) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        long duration = System.currentTimeMillis() - startTime;

        // Then
        log.info("=== 1000명 동시 예약 결과 ===");
        log.info("성공: {}, 실패: {}", successCount.get(), failCount.get());
        log.info("소요 시간: {}ms", duration);
        log.info("초당 처리량: {} TPS", threadCount * 1000.0 / duration);

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(999);
    }

    @Test
    @DisplayName("시나리오 2: 50석 동시 예약 (100명 경쟁)")
    void extreme_100_users_for_50_seats() throws InterruptedException {
        // Given
        int seatCount = 50;
        int userCount = 100;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(userCount);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(20);

        // When: 각 사용자가 랜덤 좌석 예약 시도
        for (int i = 0; i < userCount; i++) {
            executor.execute(() -> {
                try {
                    startLatch.await();

                    String userId = UUID.randomUUID().toString();

                    // 랜덤 좌석 선택
                    int randomSeat = ThreadLocalRandom.current().nextInt(1, seatCount + 1);
                    SeatIdentifier seat = new SeatIdentifier(
                            new ConcertScheduleId(scheduleId),
                            new SeatNumber(randomSeat)
                    );

                    boolean success = seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(userId),
                            Duration.ofMinutes(5)
                    );

                    if (success) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("예약 실패: {}", e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        log.info("=== 50석 동시 예약 결과 ===");
        log.info("성공: {} / 시도: {}", successCount.get(), userCount);
        log.info("예약률: {}%", successCount.get() * 100.0 / userCount);

        // 50석 중 일부는 예약되어야 함 (중복 선택으로 전부는 아닐 수 있음)
        assertThat(successCount.get()).isGreaterThan(0);
        assertThat(successCount.get()).isLessThanOrEqualTo(seatCount);
    }

    @Test
    @DisplayName("시나리오 3: 잔액 차감 극한 테스트 (500명, 잔액 100,000원)")
    void extreme_500_concurrent_payments() throws InterruptedException {
        // Given
        UUID userId = UUID.randomUUID();
        walletRepository.saveAndFlush(new UserWalletJpaEntity(userId, 100_000L));

        int threadCount = 500;
        long paymentAmount = 1000L;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(50);

        long startTime = System.currentTimeMillis();

        // When: 500명이 1000원씩 차감 시도
        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                try {
                    startLatch.await();

                    PaymentUseCase.PaymentCommand command = new PaymentUseCase.PaymentCommand(
                            userId.toString(),
                            paymentAmount,
                            UUID.randomUUID().toString()
                    );

                    paymentUseCase.pay(command);
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        long duration = System.currentTimeMillis() - startTime;

        // 최종 잔액 확인
        UserWalletJpaEntity wallet = walletRepository.findById(userId).orElseThrow();

        // Then
        log.info("=== 500명 동시 결제 결과 ===");
        log.info("성공: {}, 실패: {}", successCount.get(), failCount.get());
        log.info("최종 잔액: {} (예상: 0)", wallet.getBalance());
        log.info("소요 시간: {}ms", duration);
        log.info("초당 처리량: {} TPS", threadCount * 1000.0 / duration);

        // 100,000원 / 1,000원 = 100명 성공
        assertThat(successCount.get()).isEqualTo(100);
        assertThat(failCount.get()).isEqualTo(400);
        assertThat(wallet.getBalance()).isEqualTo(0L);
    }

    @Test
    @DisplayName("시나리오 4: 전체 플로우 통합 테스트 (토큰발급→예약→결제)")
    void extreme_full_reservation_flow() throws InterruptedException {
        // Given
        int userCount = 50;
        List<String> userIds = new ArrayList<>();

        // ===== 강제 출력 =====
        System.out.println("=== 시나리오 4 시작 ===");
        System.out.println("사용자 수: " + userCount);
        System.out.println("스케줄 ID: " + scheduleId);

        // 사용자별 지갑 생성
        for (int i = 0; i < userCount; i++) {
            UUID userId = UUID.randomUUID();
            walletRepository.save(new UserWalletJpaEntity(userId, 100_000L));
            userIds.add(userId.toString());
        }
        System.out.println("✅ Step 1 완료 - " + userCount + "명 지갑 생성");

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(userCount);
        AtomicInteger completeReservations = new AtomicInteger(0);

        // 실패 추적용
        AtomicInteger tokenFails = new AtomicInteger(0);
        AtomicInteger assignFails = new AtomicInteger(0);
        AtomicInteger confirmFails = new AtomicInteger(0);

        // 에러 메시지 수집
        List<String> errors = new CopyOnWriteArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(20);

        // When: 전체 예약 플로우 실행
        for (int i = 0; i < userCount; i++) {
            final String userId = userIds.get(i);
            final int seatNo = i + 1;
            final int userIndex = i;

            executor.execute(() -> {
                try {
                    startLatch.await();

                    // 1. 큐 토큰 발급
                    QueueUseCase.TokenInfo token;
                    try {
                        token = queueUseCase.issueToken(
                                new QueueUseCase.IssueTokenCommand(userId)
                        );

                        if (!"ACTIVE".equals(token.status())) {
                            String msg = String.format("[User %d] 토큰 비활성: status=%s, waiting=%d",
                                    userIndex, token.status(), token.waitingNumber());
                            System.out.println(msg);
                            errors.add(msg);
                            tokenFails.incrementAndGet();
                            return;
                        }
                    } catch (Exception e) {
                        String msg = String.format("[User %d] 토큰 발급 실패: %s",
                                userIndex, e.getMessage());
                        System.err.println(msg);
                        errors.add(msg);
                        tokenFails.incrementAndGet();
                        return;
                    }

                    // 2. 좌석 임시 배정
                    ReservationUseCase.TemporaryAssignResult assignResult;
                    try {
                        assignResult = reservationUseCase.temporaryAssign(
                                new ReservationUseCase.TemporaryAssignCommand(
                                        token.token(),
                                        scheduleId,
                                        seatNo
                                )
                        );
                    } catch (Exception e) {
                        String msg = String.format("[User %d] 임시 배정 실패 (seat=%d): %s",
                                userIndex, seatNo, e.getMessage());
                        System.err.println(msg);
                        errors.add(msg);
                        assignFails.incrementAndGet();
                        return;
                    }

                    // 3. 결제 및 예약 확정
                    try {
                        reservationUseCase.confirmReservation(
                                new ReservationUseCase.ConfirmReservationCommand(
                                        token.token(),
                                        assignResult.reservationId(),
                                        UUID.randomUUID().toString()
                                )
                        );
                        int count = completeReservations.incrementAndGet();
                        System.out.println(String.format("✅ [User %d] 예약 완료 (%d/%d)",
                                userIndex, count, userCount));
                    } catch (Exception e) {
                        String msg = String.format("[User %d] 예약 확정 실패: %s",
                                userIndex, e.getMessage());
                        System.err.println(msg);
                        e.printStackTrace();  // 스택트레이스도 출력
                        errors.add(msg);
                        confirmFails.incrementAndGet();
                    }

                } catch (Exception e) {
                    String msg = String.format("[User %d] 전체 플로우 실패: %s",
                            userIndex, e.getMessage());
                    System.err.println(msg);
                    e.printStackTrace();
                    errors.add(msg);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        System.out.println("✅ Step 2 - 전체 스레드 시작");

        boolean finished = endLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        System.out.println("\n=== 전체 플로우 통합 테스트 결과 ===");
        System.out.println("완료 여부: " + finished);
        System.out.println("완료된 예약: " + completeReservations.get() + " / " + userCount);
        System.out.println("토큰 발급 실패: " + tokenFails.get());
        System.out.println("임시 배정 실패: " + assignFails.get());
        System.out.println("예약 확정 실패: " + confirmFails.get());

        System.out.println("\n=== 에러 메시지 (처음 10개) ===");
        errors.stream().limit(10).forEach(System.out::println);

        assertThat(completeReservations.get())
                .as("최소 1건 이상의 예약이 완료되어야 함")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("시나리오 5: 타임아웃 경계 테스트 (만료 직전 대량 요청)")
    void extreme_timeout_boundary_test() throws InterruptedException {
        // Given
        SeatIdentifier seat = new SeatIdentifier(
                new ConcertScheduleId(scheduleId),
                new SeatNumber(1)
        );

        String user1 = UUID.randomUUID().toString();
        String user2 = UUID.randomUUID().toString();

        // 1. 별도 트랜잭션으로 점유
        transactionTemplate.execute(status -> {
            seatHoldPort.tryHold(seat, UserId.ofString(user1), Duration.ofSeconds(3));
            return null;
        }); // 여기서 커밋!

        log.info("완료 - User1 점유");

        // 2. 3.5초 대기
        Thread.sleep(3500);
        log.info("완료 - 만료 대기");

        // 3. 별도 트랜잭션으로 청소
        Integer deleted = transactionTemplate.execute(status -> {
            int count = seatHoldRepository.deleteExpiredHolds(LocalDateTime.now());
            log.info("삭제된 레코드: {}", count);
            return count;
        }); // 여기서 커밋. 이제 다른 트랜잭션에서도 보임

        log.info("완료 - 청소 완료 ({}건 삭제)", deleted);

        // 짧은 대기
        Thread.sleep(100);

        // 4. 검증 - 단일 사용자 테스트
        String testUser = UUID.randomUUID().toString();
        Boolean singleTest = transactionTemplate.execute(status -> {
            return seatHoldPort.tryHold(
                    seat,
                    UserId.ofString(testUser),
                    Duration.ofMinutes(5)
            );
        });

        log.info("단일 사용자 테스트: {}", singleTest);

        if (Boolean.FALSE.equals(singleTest)) {
            log.error("단일 사용자도 점유 실패! 테스트 중단");

            // 디버깅 정보
            transactionTemplate.execute(status -> {
                long count = seatHoldRepository.count();
                log.error("현재 DB에 남은 홀드: {}건", count);

                seatHoldRepository.findByScheduleIdAndSeatNumber(scheduleId, 1)
                        .ifPresent(h -> {
                            log.error("홀드 정보: userId={}, expiresAt={}, now={}",
                                    h.getUserId(), h.getExpiresAt(), LocalDateTime.now());
                        });
                return null;
            });

            fail("단일 사용자 점유 실패 - 청소가 제대로 안됨");
        }

        // 5. 테스트 점유 해제
        transactionTemplate.execute(status -> {
            seatHoldPort.release(seat);
            return null;
        });

        log.info("완료 - 테스트 점유 해제");
        Thread.sleep(100);

        // When: 100명 동시 요청
        int threadCount = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(20);

        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                try {
                    startLatch.await();

                    boolean success = seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(user2),
                            Duration.ofMinutes(5)
                    );

                    if (success) {
                        int count = successCount.incrementAndGet();
                        log.info("성공 #{}", count);
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("예외: {}", e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = endLatch.await(30, TimeUnit.SECONDS);  // 타임아웃 늘림
        executor.shutdown();

        // Then
        log.info("=== 타임아웃 경계 테스트 결과 ===");
        log.info("완료 여부: {}", finished);
        log.info("성공: {}", successCount.get());
        log.info("실패: {}", failCount.get());

        // 1명만 성공해야 함
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(99);
    }
}