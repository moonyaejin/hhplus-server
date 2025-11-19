package kr.hhplus.be.server.integration;

import kr.hhplus.be.server.application.port.in.PaymentUseCase;
import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import kr.hhplus.be.server.concurrency.config.TestTaskExecutorConfig;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.reservation.SeatIdentifier;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.SeatHoldJpaRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개선된 동시성 통합 테스트
 * - TaskExecutor 사용으로 안정적인 트랜잭션 관리
 * - CountDownLatch를 활용한 동시성 제어
 * - 테스트 격리를 통한 안정성 향상
 */
@Slf4j
@SpringBootTest
@Import(TestTaskExecutorConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class ConcurrencyIntegrationTest {

    @Autowired
    private SeatHoldPort seatHoldPort;

    @Autowired
    private PaymentUseCase paymentUseCase;

    @Autowired
    private SeatHoldJpaRepository seatHoldRepository;

    @Autowired
    @Qualifier("concurrencyTestExecutor")
    private TaskExecutor taskExecutor;


    @Autowired
    @Qualifier("singleThreadTestExecutor")
    private TaskExecutor singleThreadExecutor;

    @BeforeEach
    void setUp() {
        // 모든 테스트 전 데이터 완전 정리
        log.info("테스트 시작 전 데이터 정리");
        seatHoldRepository.deleteAll();
        seatHoldRepository.flush();
    }

    @AfterEach
    void tearDown() {
        // 테스트 후 정리
        seatHoldRepository.deleteAll();
        seatHoldRepository.flush();
    }

    @Test
    @DisplayName("서로 다른 좌석은 동시에 예약 가능 - TaskExecutor 버전")
    void differentSeatsCanBeReservedConcurrently_withTaskExecutor() throws InterruptedException {
        // Given
        int seatCount = 10;
        Long scheduleId = 10L;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(seatCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When - TaskExecutor 사용
        for (int i = 1; i <= seatCount; i++) {
            final int seatNo = i;
            final String userId = UUID.randomUUID().toString();

            taskExecutor.execute(() -> {
                try {
                    startLatch.await(); // 동시 시작 대기

                    SeatIdentifier seat = SeatIdentifier.of(scheduleId, seatNo);

                    boolean success = seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(userId),
                            Duration.ofMinutes(5)
                    );

                    if (success) {
                        int count = successCount.incrementAndGet();
                        log.info("좌석 {} 예약 성공 (#{}) - userId: {}",
                                seatNo, count, userId.substring(0, 8));
                    } else {
                        failCount.incrementAndGet();
                        log.warn("좌석 {} 예약 실패 - userId: {}",
                                seatNo, userId.substring(0, 8));
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("좌석 {} 예약 중 오류: ", seatNo, e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 모든 스레드 동시 시작
        startLatch.countDown();
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);

        // Then
        log.info("=== 서로 다른 좌석 동시 예약 결과 ===");
        log.info("완료 여부: {}", completed);
        log.info("성공: {}, 실패: {}", successCount.get(), failCount.get());

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(seatCount);
        assertThat(failCount.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("같은 좌석 동시 예약 - 1명만 성공")
    void sameSeatConcurrentReservation_onlyOneSucceeds() throws InterruptedException {
        // Given
        Long scheduleId = 20L;
        int seatNo = 1;
        SeatIdentifier seat = SeatIdentifier.of(scheduleId, seatNo);

        int userCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(userCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When
        for (int i = 0; i < userCount; i++) {
            final String userId = UUID.randomUUID().toString();
            final int userNum = i;

            taskExecutor.execute(() -> {
                try {
                    startLatch.await();

                    boolean success = seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(userId),
                            Duration.ofMinutes(5)
                    );

                    if (success) {
                        int count = successCount.incrementAndGet();
                        log.info("User {} 성공 (총 {}명째)", userId.substring(0, 8), count);
                    } else {
                        failCount.incrementAndGet();
                        log.debug("User {} 실패", userId.substring(0, 8));
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("User {} 예외 발생: ", userNum, e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);

        // Then
        log.info("=== 같은 좌석 동시 예약 결과 ===");
        log.info("완료: {}, 성공: {}, 실패: {}", completed, successCount.get(), failCount.get());

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(userCount - 1);
    }

    @Test
    @DisplayName("비동기 동시성 테스트 - 같은 좌석 경쟁 (CountDownLatch)")
    void asyncTestWithCountDownLatch_sameSeat() throws Exception {
        // Given
        Long scheduleId = 30L;
        int seatNo = 1;
        SeatIdentifier seat = SeatIdentifier.of(scheduleId, seatNo);

        // 해당 좌석이 비어있는지 확인
        boolean isAlreadyHeld = seatHoldRepository
                .findByScheduleIdAndSeatNumber(scheduleId, seatNo)
                .filter(hold -> !hold.isExpired())
                .isPresent();

        assertThat(isAlreadyHeld).isFalse()
                .withFailMessage("테스트 시작 전 좌석이 이미 예약되어 있습니다!");

        // CountDownLatch 사용
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When - TaskExecutor 사용
        for (int i = 0; i < 2; i++) {
            final String userId = UUID.randomUUID().toString();
            final int threadNum = i + 1;

            taskExecutor.execute(() -> {
                try {
                    startLatch.await();  // 동시 시작 대기

                    log.debug("Thread-{} 시작", threadNum);
                    boolean result = seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(userId),
                            Duration.ofMinutes(5)
                    );

                    if (result) {
                        successCount.incrementAndGet();
                        log.info("Thread-{} 성공", threadNum);
                    } else {
                        failCount.incrementAndGet();
                        log.debug("Thread-{} 실패", threadNum);
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.debug("Thread-{} 예외: {}", threadNum, e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 동시 시작
        startLatch.countDown();

        // 완료 대기
        boolean completed = endLatch.await(5, TimeUnit.SECONDS);

        // Then
        log.info("테스트 결과 - 성공: {}, 실패: {}", successCount.get(), failCount.get());

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("비동기 동시성 테스트 - 다른 좌석 동시 예약 (CountDownLatch)")
    void asyncTestWithCountDownLatch_differentSeats() throws Exception {
        // Given - 서로 다른 좌석
        Long scheduleId = 40L;
        SeatIdentifier seat1 = SeatIdentifier.of(scheduleId, 1);
        SeatIdentifier seat2 = SeatIdentifier.of(scheduleId, 2);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);
        AtomicBoolean result1 = new AtomicBoolean(false);
        AtomicBoolean result2 = new AtomicBoolean(false);

        // When - 다른 좌석 동시 예약
        taskExecutor.execute(() -> {
            try {
                startLatch.await();
                log.debug("Thread-1: 좌석 1 예약 시도");
                boolean success = seatHoldPort.tryHold(
                        seat1,
                        UserId.ofString(UUID.randomUUID().toString()),
                        Duration.ofMinutes(5)
                );
                result1.set(success);
                log.info("Thread-1 결과: {}", success);
            } catch (Exception e) {
                log.error("Thread-1 예외: ", e);
                result1.set(false);
            } finally {
                endLatch.countDown();
            }
        });

        taskExecutor.execute(() -> {
            try {
                startLatch.await();
                log.debug("Thread-2: 좌석 2 예약 시도");
                boolean success = seatHoldPort.tryHold(
                        seat2,
                        UserId.ofString(UUID.randomUUID().toString()),
                        Duration.ofMinutes(5)
                );
                result2.set(success);
                log.info("Thread-2 결과: {}", success);
            } catch (Exception e) {
                log.error("Thread-2 예외: ", e);
                result2.set(false);
            } finally {
                endLatch.countDown();
            }
        });

        // 동시 시작
        startLatch.countDown();

        // 완료 대기
        boolean completed = endLatch.await(5, TimeUnit.SECONDS);

        // Then - 다른 좌석이므로 둘 다 성공
        log.info("결과 - Seat1: {}, Seat2: {}", result1.get(), result2.get());

        assertThat(completed).isTrue();
        assertThat(result1.get()).isTrue();
        assertThat(result2.get()).isTrue();
    }

    @Test
    @DisplayName("대규모 동시성 테스트 - 10개 스레드가 같은 좌석 경쟁")
    void massiveConcurrencyTest_sameSeat() throws Exception {
        // Given
        Long scheduleId = 50L;
        int seatNo = 1;
        SeatIdentifier seat = SeatIdentifier.of(scheduleId, seatNo);

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> successfulUsers = new ArrayList<>();

        // When - 10개의 스레드가 동시에 같은 좌석 예약 시도
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            final String userId = UUID.randomUUID().toString();

            taskExecutor.execute(() -> {
                try {
                    startLatch.await();  // 동시 시작 대기

                    log.debug("Thread-{} 시작", idx);
                    boolean result = seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(userId),
                            Duration.ofMinutes(5)
                    );

                    if (result) {
                        successCount.incrementAndGet();
                        synchronized (successfulUsers) {
                            successfulUsers.add(userId);
                        }
                        log.info("Thread-{} 성공 (userId: {})", idx, userId.substring(0, 8));
                    } else {
                        failCount.incrementAndGet();
                        log.debug("Thread-{} 실패", idx);
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.debug("Thread-{} 예외: {}", idx, e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 동시 시작
        startLatch.countDown();

        // 완료 대기
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);

        // Then
        log.info("=== 대규모 동시성 테스트 결과 ===");
        log.info("완료: {}, 성공: {}, 실패: {}", completed, successCount.get(), failCount.get());
        if (!successfulUsers.isEmpty()) {
            log.info("성공한 사용자: {}", successfulUsers.get(0).substring(0, 8));
        }

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(threadCount - 1);
    }

    @Test
    @DisplayName("홀드 만료 후 재예약 가능 테스트")
    void holdExpiration_allowsNewReservation() throws Exception {
        // Given
        Long scheduleId = 60L;
        int seatNo = 1;
        SeatIdentifier seat = SeatIdentifier.of(scheduleId, seatNo);
        String userId1 = UUID.randomUUID().toString();
        String userId2 = UUID.randomUUID().toString();

        // When - 짧은 홀드 시간으로 첫 번째 예약
        boolean firstHold = seatHoldPort.tryHold(
                seat,
                UserId.ofString(userId1),
                Duration.ofMillis(100)  // 100ms 후 만료
        );

        // 만료 전 시도 - 실패해야 함
        boolean secondHoldBeforeExpiry = seatHoldPort.tryHold(
                seat,
                UserId.ofString(userId2),
                Duration.ofMinutes(5)
        );

        // 만료 대기
        Thread.sleep(150);

        // 만료 후 시도 - 성공해야 함
        boolean secondHoldAfterExpiry = seatHoldPort.tryHold(
                seat,
                UserId.ofString(userId2),
                Duration.ofMinutes(5)
        );

        // Then
        log.info("홀드 만료 테스트 결과 - 첫번째: {}, 만료전: {}, 만료후: {}",
                firstHold, secondHoldBeforeExpiry, secondHoldAfterExpiry);

        assertThat(firstHold).isTrue();
        assertThat(secondHoldBeforeExpiry).isFalse();
        assertThat(secondHoldAfterExpiry).isTrue();
    }

    @Test
    @DisplayName("순차 처리 vs 동시 처리 성능 비교")
    void performanceComparison() throws Exception {
        int operationCount = 10;

        // 순차 처리 (SingleThreadExecutor 사용)
        long sequentialStart = System.currentTimeMillis();
        CountDownLatch sequentialLatch = new CountDownLatch(operationCount);

        for (int i = 0; i < operationCount; i++) {
            final int seatNo = i + 1;
            singleThreadExecutor.execute(() -> {
                try {
                    // 각각 다른 좌석 예약 (충돌 없음)
                    SeatIdentifier seat = SeatIdentifier.of(100L, seatNo);
                    seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(UUID.randomUUID().toString()),
                            Duration.ofMinutes(5)
                    );
                    Thread.sleep(10); // 작업 시뮬레이션
                } catch (Exception e) {
                    log.error("Sequential error: ", e);
                } finally {
                    sequentialLatch.countDown();
                }
            });
        }

        sequentialLatch.await(30, TimeUnit.SECONDS);
        long sequentialTime = System.currentTimeMillis() - sequentialStart;

        // 동시 처리
        long concurrentStart = System.currentTimeMillis();
        CountDownLatch concurrentLatch = new CountDownLatch(operationCount);

        for (int i = 0; i < operationCount; i++) {
            final int seatNo = i + 101; // 다른 좌석 범위
            taskExecutor.execute(() -> {
                try {
                    SeatIdentifier seat = SeatIdentifier.of(100L, seatNo);
                    seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(UUID.randomUUID().toString()),
                            Duration.ofMinutes(5)
                    );
                    Thread.sleep(10); // 작업 시뮬레이션
                } catch (Exception e) {
                    log.error("Concurrent error: ", e);
                } finally {
                    concurrentLatch.countDown();
                }
            });
        }

        concurrentLatch.await(30, TimeUnit.SECONDS);
        long concurrentTime = System.currentTimeMillis() - concurrentStart;

        // 결과 비교
        log.info("=== 성능 비교 결과 ===");
        log.info("순차 처리 시간: {}ms", sequentialTime);
        log.info("동시 처리 시간: {}ms", concurrentTime);
        log.info("성능 향상: {:.2f}배", (double) sequentialTime / concurrentTime);

        // 동시 처리가 더 빨라야 함
        assertThat(concurrentTime).isLessThan(sequentialTime);
    }

    /**
     * 트랜잭션 컨텍스트 상태 로깅
     */
    private void logTransactionContext(String context) {
        boolean isActive = TransactionSynchronizationManager.isActualTransactionActive();
        String txName = TransactionSynchronizationManager.getCurrentTransactionName();
        Integer isolationLevel = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();

        log.debug("[{}] TX - Active: {}, Name: {}, Isolation: {}, Thread: {}",
                context, isActive, txName, isolationLevel,
                Thread.currentThread().getName());
    }
}