package kr.hhplus.be.server.concurrency;

import kr.hhplus.be.server.application.port.in.PaymentUseCase;
import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import kr.hhplus.be.server.concurrency.config.TestTaskExecutorConfig;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.reservation.SeatIdentifier;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWalletJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.SeatHoldJpaRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개선된 극한 동시성 테스트
 * - 대규모 동시 요청 시나리오
 * - TaskExecutor로 안정적인 스레드 관리
 */
@Disabled("성능 테스트. 시간이 오래 걸려 CI에서 제외. 로컬에서 필요시 직접 실행")
@Slf4j
@SpringBootTest
@Import(TestTaskExecutorConfig.class)
@TestPropertySource(properties = {
        "app.payment.use-conditional-update=true"  // 대규모 테스트에는 조건부 UPDATE가 효율적
})
@DisplayName("[극한 상황] 동시성 제어 테스트 - TaskExecutor 버전")
public class ExtremeConcurrencyTest {

    @Autowired
    private SeatHoldPort seatHoldPort;

    @Autowired
    private PaymentUseCase paymentUseCase;

    @Autowired
    private UserWalletJpaRepository walletRepository;

    @Autowired
    private SeatHoldJpaRepository seatHoldRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    @Qualifier("extremeTestExecutor")
    private TaskExecutor extremeExecutor;

    @Test
    @DisplayName("시나리오 1: 1000명이 동시에 같은 좌석 예약")
    void extremeConcurrencyTest_1000UsersFor1Seat() throws InterruptedException {
        // Given
        Long scheduleId = 1000L;
        int seatNo = 1;
        SeatIdentifier seat = SeatIdentifier.of(scheduleId, seatNo);

        int threadCount = 1000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // When - ExtremExecutor 사용 (100개 스레드 풀)
        for (int i = 0; i < threadCount; i++) {
            final String userId = UUID.randomUUID().toString();
            final int userNum = i;

            extremeExecutor.execute(() -> {
                try {
                    startLatch.await(); // 모든 스레드 동시 시작

                    // 처음 10개 스레드만 상태 로깅
                    if (userNum < 10) {
                        log.debug("User-{} starting, Thread: {}",
                                userNum, Thread.currentThread().getName());
                    }

                    boolean success = seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(userId),
                            Duration.ofMinutes(5)
                    );

                    if (success) {
                        int count = successCount.incrementAndGet();
                        log.info("🎉 성공! User #{} got the seat", count);
                    } else {
                        failCount.incrementAndGet();
                    }

                } catch (Exception e) {
                    failCount.incrementAndGet();
                    if (userNum < 10) {
                        log.error("User-{} failed with exception: ", userNum, e);
                    }
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 모든 스레드 동시 시작
        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);

        long duration = System.currentTimeMillis() - startTime;

        // Then
        log.info("=== 1000명 동시 좌석 예약 결과 ===");
        log.info("완료 여부: {}", completed);
        log.info("성공: {}, 실패: {}", successCount.get(), failCount.get());
        log.info("처리 시간: {}ms", duration);
        log.info("TPS: {:.2f}", threadCount * 1000.0 / duration);

        // TaskExecutor 상태 출력
        logExecutorStatus();

        // 1명만 성공해야 함
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(999);
    }

    @Test
    @DisplayName("시나리오 2: 500명 동시 결제 (잔액 소진)")
    void extremeConcurrencyTest_500PaymentsUntilExhaustion() throws InterruptedException {
        // Given
        UUID userId = UUID.randomUUID();
        long initialBalance = 100_000L;
        long paymentAmount = 1_000L;

        walletRepository.saveAndFlush(new UserWalletJpaEntity(userId, initialBalance));

        int threadCount = 500;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // When: 500명이 1000원씩 차감 시도 (최대 100명 성공 가능)
        for (int i = 0; i < threadCount; i++) {
            final int paymentNum = i;

            extremeExecutor.execute(() -> {
                try {
                    startLatch.await();

                    // 처음 5개만 로깅
                    if (paymentNum < 5) {
                        log.debug("Payment-{} starting", paymentNum);
                    }

                    PaymentUseCase.PaymentCommand command = new PaymentUseCase.PaymentCommand(
                            userId.toString(),
                            paymentAmount,
                            UUID.randomUUID().toString()
                    );

                    paymentUseCase.pay(command);
                    int count = successCount.incrementAndGet();

                    if (count <= 10) { // 처음 10개 성공만 로깅
                        log.info("결제 성공 #{}", count);
                    }

                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);

        long duration = System.currentTimeMillis() - startTime;

        // 최종 잔액 확인
        UserWalletJpaEntity wallet = walletRepository.findById(userId).orElseThrow();

        // Then
        log.info("=== 500명 동시 결제 결과 ===");
        log.info("완료: {}", completed);
        log.info("성공: {}, 실패: {}", successCount.get(), failCount.get());
        log.info("최종 잔액: {} (예상: 0)", wallet.getBalance());
        log.info("처리 시간: {}ms", duration);
        log.info("TPS: {:.2f}", threadCount * 1000.0 / duration);

        logExecutorStatus();

        // 100,000원 / 1,000원 = 100명 성공
        assertThat(successCount.get()).isEqualTo(100);
        assertThat(failCount.get()).isEqualTo(400);
        assertThat(wallet.getBalance()).isEqualTo(0L);
    }

    @Test
    @DisplayName("시나리오 3: Mixed 워크로드 - 예약과 결제 동시 진행")
    void extremeConcurrencyTest_mixedWorkload() throws InterruptedException {
        // Given
        int reservationThreads = 200;
        int paymentThreads = 200;
        int totalThreads = reservationThreads + paymentThreads;

        // 좌석 예약용 데이터
        Long scheduleId = 2000L;
        SeatIdentifier seat = SeatIdentifier.of(scheduleId, 1);

        // 결제용 데이터
        UUID paymentUserId = UUID.randomUUID();
        walletRepository.saveAndFlush(new UserWalletJpaEntity(paymentUserId, 50_000L));

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalThreads);

        AtomicInteger reservationSuccess = new AtomicInteger(0);
        AtomicInteger paymentSuccess = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // When - 예약과 결제를 동시에
        // 예약 스레드들
        for (int i = 0; i < reservationThreads; i++) {
            extremeExecutor.execute(() -> {
                try {
                    startLatch.await();

                    boolean success = seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(UUID.randomUUID().toString()),
                            Duration.ofMinutes(5)
                    );

                    if (success) {
                        reservationSuccess.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Reservation error: ", e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 결제 스레드들
        for (int i = 0; i < paymentThreads; i++) {
            extremeExecutor.execute(() -> {
                try {
                    startLatch.await();

                    PaymentUseCase.PaymentCommand command = new PaymentUseCase.PaymentCommand(
                            paymentUserId.toString(),
                            1_000L,
                            UUID.randomUUID().toString()
                    );

                    paymentUseCase.pay(command);
                    paymentSuccess.incrementAndGet();

                } catch (Exception e) {
                    // 실패는 예상됨
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(30, TimeUnit.SECONDS);

        long duration = System.currentTimeMillis() - startTime;

        // Then
        log.info("=== Mixed 워크로드 결과 ===");
        log.info("완료: {}", completed);
        log.info("예약 성공: {}/{}개", reservationSuccess.get(), reservationThreads);
        log.info("결제 성공: {}/{}개", paymentSuccess.get(), paymentThreads);
        log.info("총 처리 시간: {}ms", duration);
        log.info("전체 TPS: {:.2f}", totalThreads * 1000.0 / duration);

        logExecutorStatus();

        // 좌석은 1명만, 결제는 50명까지 성공
        assertThat(reservationSuccess.get()).isEqualTo(1);
        assertThat(paymentSuccess.get()).isEqualTo(50);
    }

    /**
     * TaskExecutor 상태 로깅
     */
    private void logExecutorStatus() {
        if (extremeExecutor instanceof ThreadPoolTaskExecutor) {
            ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) extremeExecutor;
            log.info("=== Executor 상태 ===");
            log.info("Core Pool Size: {}", executor.getCorePoolSize());
            log.info("Max Pool Size: {}", executor.getMaxPoolSize());
            log.info("Current Pool Size: {}", executor.getPoolSize());
            log.info("Active Threads: {}", executor.getActiveCount());
            log.info("Queue Size: {}", executor.getThreadPoolExecutor().getQueue().size());
            log.info("Completed Tasks: {}", executor.getThreadPoolExecutor().getCompletedTaskCount());
        }
    }
}