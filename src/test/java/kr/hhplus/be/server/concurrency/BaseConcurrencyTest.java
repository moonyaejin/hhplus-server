package kr.hhplus.be.server.concurrency;

import kr.hhplus.be.server.application.port.in.PaymentUseCase;
import kr.hhplus.be.server.application.port.out.SeatHoldPort;
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
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@SpringBootTest
public abstract class BaseConcurrencyTest {

    @Autowired
    protected SeatHoldPort seatHoldPort;

    @Autowired
    protected PaymentUseCase paymentUseCase;

    @Autowired
    protected UserWalletJpaRepository walletRepository;

    @Autowired
    protected SeatHoldJpaRepository seatHoldRepository;

    @BeforeEach
    void setUp() {
        // 테스트 전 데이터 정리
        seatHoldRepository.deleteAll();
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

        ExecutorService executor = Executors.newFixedThreadPool(10);

        // When
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.execute(() -> {
                try {
                    startLatch.await(); // 모든 스레드 동시 시작

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
                    log.error("Thread {} 실패: {}", idx, e.getMessage());
                    failCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 시작!
        endLatch.await();
        executor.shutdown();

        // Then
        log.info("좌석 예약 결과 - 성공: {}, 실패: {}", successCount.get(), failCount.get());

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

        ExecutorService executor = Executors.newFixedThreadPool(10);

        // When: 20번 x 1000원 = 20,000원 시도 (잔액은 10,000원)
        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                try {
                    startLatch.await();

                    PaymentUseCase.PaymentCommand command = new PaymentUseCase.PaymentCommand(
                            userId.toString(),
                            1000L,
                            UUID.randomUUID().toString()
                    );

                    paymentUseCase.pay(command);
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    // 잔액 부족 예외는 정상 동작
                    failCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // 최종 잔액 확인
        UserWalletJpaEntity wallet = walletRepository.findById(userId).orElseThrow();

        log.info("잔액 차감 결과 - 성공: {}, 실패: {}, 최종잔액: {}",
                successCount.get(), failCount.get(), wallet.getBalance());

        // 검증은 각 테스트 클래스에서
        assertBalanceDeductionResult(successCount.get(), failCount.get(), wallet.getBalance());
    }

    /**
     * 타임아웃 테스트 - 2초 점유 후 재점유 가능 확인
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
        // User1이 2초 점유
        boolean hold1 = seatHoldPort.tryHold(
                seat,
                UserId.ofString(user1),
                Duration.ofSeconds(2)
        );

        // 1초 후: 아직 점유 중
        Thread.sleep(1000);
        boolean hold2 = seatHoldPort.tryHold(
                seat,
                UserId.ofString(user2),
                Duration.ofMinutes(5)
        );

        // 2초 후: 만료되어 재점유 가능
        Thread.sleep(1100); // 여유를 두고 2.1초
        boolean hold3 = seatHoldPort.tryHold(
                seat,
                UserId.ofString(user2),
                Duration.ofMinutes(5)
        );

        // Then
        log.info("타임아웃 테스트 결과 - 첫점유: {}, 중간점유: {}, 만료후점유: {}",
                hold1, hold2, hold3);

        assertTimeoutResult(hold1, hold2, hold3);
    }

    // 각 테스트 클래스에서 구현할 검증 메서드
    protected abstract void assertSeatReservationResult(int success, int fail);
    protected abstract void assertBalanceDeductionResult(int success, int fail, long finalBalance);
    protected abstract void assertTimeoutResult(boolean hold1, boolean hold2, boolean hold3);
}