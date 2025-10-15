package kr.hhplus.be.server.concurrency;

import kr.hhplus.be.server.application.port.in.PaymentUseCase;
import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import kr.hhplus.be.server.concurrency.config.TestTaskExecutorConfig;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import kr.hhplus.be.server.domain.reservation.SeatIdentifier;
import kr.hhplus.be.server.domain.reservation.SeatNumber;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWalletJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.SeatHoldJpaRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 개선된 동시성 테스트 베이스 클래스
 * - Spring TaskExecutor 사용으로 트랜잭션 컨텍스트 관리 개선
 * - 테스트별로 적절한 Executor 선택 가능
 */
@Slf4j
@SpringBootTest
@Import(TestTaskExecutorConfig.class)
public abstract class BaseConcurrencyTest {

    @Autowired
    protected SeatHoldPort seatHoldPort;

    @Autowired
    protected PaymentUseCase paymentUseCase;

    @Autowired
    protected UserWalletJpaRepository walletRepository;

    @Autowired
    protected SeatHoldJpaRepository seatHoldRepository;

    @Autowired
    @Qualifier("concurrencyTestExecutor")
    protected TaskExecutor taskExecutor;

    @Autowired
    @Qualifier("extremeTestExecutor")
    protected TaskExecutor extremeTaskExecutor;

    @Autowired
    @Qualifier("singleThreadTestExecutor")
    protected TaskExecutor singleThreadExecutor;

    @BeforeEach
    void setUp() {
        // 테스트 전 데이터 정리
        seatHoldRepository.deleteAll();

        // 트랜잭션 컨텍스트 상태 로깅 (디버깅용)
        logTransactionContext("테스트 시작");
    }

    /**
     * 좌석 동시 예약 테스트 - 100명이 같은 좌석 예약 시도
     */
    protected void testConcurrentSeatReservation() throws InterruptedException {
        // Given
        SeatIdentifier seat = new SeatIdentifier(
                new ConcertScheduleId(100L),
                new SeatNumber(1)
        );

        int threadCount = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When - TaskExecutor 사용
        for (int i = 0; i < threadCount; i++) {
            final String userId = UUID.randomUUID().toString();
            final int threadNum = i;

            taskExecutor.execute(() -> {
                try {
                    // 디버깅: 스레드별 트랜잭션 컨텍스트 확인
                    if (threadNum < 5) { // 처음 5개만 로깅
                        logTransactionContext("Thread-" + threadNum);
                    }

                    startLatch.await(); // 모든 스레드 동시 시작

                    boolean success = seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(userId),
                            Duration.ofMinutes(5)
                    );

                    if (success) {
                        successCount.incrementAndGet();
                        log.debug("좌석 예약 성공: userId={}", userId.substring(0, 8));
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Thread {} 실패: {}", threadNum, e.getMessage());
                    failCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 시작!
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);

        // TaskExecutor 종료 대기
        waitForTaskExecutorCompletion();

        // Then
        log.info("좌석 예약 결과 - 성공: {}, 실패: {}, 완료: {}",
                successCount.get(), failCount.get(), completed);

        // 검증은 각 테스트 클래스에서
        assertSeatReservationResult(successCount.get(), failCount.get());
    }

    /**
     * 잔액 동시 차감 테스트 - 20명이 1000원씩 차감 시도 (잔액 10000원)
     */
    protected void testConcurrentBalanceDeduction() throws InterruptedException {
        // Given
        UUID userId = UUID.randomUUID();
        walletRepository.saveAndFlush(new UserWalletJpaEntity(userId, 10000L));

        int threadCount = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When: 20번 x 1000원 = 20,000원 시도 (잔액은 10,000원)
        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i;

            taskExecutor.execute(() -> {
                try {
                    startLatch.await();

                    // 트랜잭션 컨텍스트 확인 (디버깅)
                    if (threadNum < 3) {
                        logTransactionContext("Payment-" + threadNum);
                    }

                    PaymentUseCase.PaymentCommand command = new PaymentUseCase.PaymentCommand(
                            userId.toString(),
                            1000L,
                            UUID.randomUUID().toString()
                    );

                    paymentUseCase.pay(command);
                    int count = successCount.incrementAndGet();
                    log.debug("결제 성공 #{}", count);

                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.debug("결제 실패: {}", e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);

        // TaskExecutor 종료 대기
        waitForTaskExecutorCompletion();

        // 최종 잔액 확인
        UserWalletJpaEntity wallet = walletRepository.findById(userId).orElseThrow();

        // Then
        log.info("결제 결과 - 성공: {}, 실패: {}, 잔액: {}, 완료: {}",
                successCount.get(), failCount.get(), wallet.getBalance(), completed);

        assertBalanceDeductionResult(successCount.get(), failCount.get(), wallet.getBalance());
    }

    /**
     * 타임아웃 테스트 - 만료 후 재점유 가능 확인
     */
    protected void testTimeoutRelease() throws InterruptedException {
        // Given
        SeatIdentifier seat = new SeatIdentifier(
                new ConcertScheduleId(200L),
                new SeatNumber(1)
        );

        String user1 = UUID.randomUUID().toString();
        String user2 = UUID.randomUUID().toString();

        // When
        boolean hold1 = seatHoldPort.tryHold(seat, UserId.ofString(user1), Duration.ofMillis(100));
        Thread.sleep(50); // 만료 전
        boolean hold2 = seatHoldPort.tryHold(seat, UserId.ofString(user2), Duration.ofMinutes(5));
        Thread.sleep(100); // 만료 후
        boolean hold3 = seatHoldPort.tryHold(seat, UserId.ofString(user2), Duration.ofMinutes(5));

        // Then
        log.info("타임아웃 테스트 - hold1: {}, hold2: {}, hold3: {}", hold1, hold2, hold3);
        assertTimeoutResult(hold1, hold2, hold3);
    }

    /**
     * 대규모 동시성 테스트를 위한 헬퍼 메서드
     * extremeTaskExecutor 사용
     */
    protected void runExtremeTest(int threadCount, Runnable task) throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            extremeTaskExecutor.execute(() -> {
                try {
                    startLatch.await();
                    task.run();
                } catch (Exception e) {
                    log.error("Extreme test 실행 중 오류: ", e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(60, TimeUnit.SECONDS);
        log.info("Extreme test 완료: {}, 스레드 수: {}", completed, threadCount);
    }

    /**
     * 트랜잭션 컨텍스트 상태 로깅 (디버깅용)
     */
    protected void logTransactionContext(String context) {
        boolean isActive = TransactionSynchronizationManager.isActualTransactionActive();
        String txName = TransactionSynchronizationManager.getCurrentTransactionName();
        Integer isolationLevel = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        boolean isSynchronizationActive = TransactionSynchronizationManager.isSynchronizationActive();

        log.debug("[{}] TX Context - Active: {}, Name: {}, Isolation: {}, Sync: {}, Thread: {}",
                context, isActive, txName, isolationLevel, isSynchronizationActive,
                Thread.currentThread().getName());
    }

    /**
     * TaskExecutor가 모든 작업을 완료할 때까지 대기
     */
    protected void waitForTaskExecutorCompletion() {
        if (taskExecutor instanceof ThreadPoolTaskExecutor) {
            ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) taskExecutor;
            // 활성 스레드가 0이 될 때까지 대기
            while (executor.getActiveCount() > 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    // 추상 메서드들 - 각 테스트 클래스에서 구현
    protected abstract void assertSeatReservationResult(int success, int fail);
    protected abstract void assertBalanceDeductionResult(int success, int fail, long finalBalance);
    protected abstract void assertTimeoutResult(boolean hold1, boolean hold2, boolean hold3);
}