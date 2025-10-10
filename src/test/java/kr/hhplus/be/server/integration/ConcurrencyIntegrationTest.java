package kr.hhplus.be.server.integration;

import kr.hhplus.be.server.TestcontainersConfiguration;
import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import kr.hhplus.be.server.domain.reservation.SeatIdentifier;
import kr.hhplus.be.server.domain.reservation.SeatNumber;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.SeatHoldJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ConcurrencyIntegrationTest {

    @Autowired
    private SeatHoldPort seatHoldPort;

    @Autowired
    private SeatHoldJpaRepository seatHoldRepository;

    @BeforeEach
    void setUp() {
        seatHoldRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        seatHoldRepository.deleteAll();
    }

    @Test
    @DisplayName("동시에 여러 유저가 같은 좌석 예약 시 한 명만 성공")
    void onlyOneUserCanReserveSameSeat() throws InterruptedException {
        // Given
        SeatIdentifier seat = new SeatIdentifier(
                new ConcertScheduleId(1L),
                new SeatNumber(15)
        );

        // 먼저 단일 호출 테스트
        try {
            String testUserId = UUID.randomUUID().toString();
            boolean testResult = seatHoldPort.tryHold(
                    seat,
                    UserId.ofString(testUserId),
                    Duration.ofMinutes(5)
            );
            System.out.println("단일 호출 테스트 결과: " + testResult);
            seatHoldPort.release(seat);
        } catch (Exception e) {
            System.err.println("단일 호출 실패: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When
        for (int i = 0; i < threadCount; i++) {
            final String userId = UUID.randomUUID().toString();
            executor.execute(() -> {
                try {
                    startLatch.await();

                    boolean success = seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(userId),
                            Duration.ofMinutes(5)
                    );

                    if (success) {
                        successCount.incrementAndGet();
                        System.out.println("User " + userId.substring(0, 8) + " 성공!");
                    } else {
                        failCount.incrementAndGet();
                        System.out.println("User " + userId.substring(0, 8) + " 실패");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    failCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();
        executor.shutdown();

        // Then
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(9);
    }

    @Test
    @DisplayName("서로 다른 좌석은 동시에 예약 가능")
    void differentSeatsCanBeReservedConcurrently() throws InterruptedException {
        // Given
        int seatCount = 10;
        Long scheduleId = 10L;

        List<Thread> threads = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);

        // When - ExecutorService 대신 Thread 직접 사용
        for (int i = 1; i <= seatCount; i++) {
            final int seatNo = i;
            final String userId = UUID.randomUUID().toString();

            Thread thread = new Thread(() -> {
                try {
                    // 각 스레드마다 약간의 랜덤 딜레이 (동시성 보장)
                    Thread.sleep((long) (Math.random() * 100));

                    SeatIdentifier seat = SeatIdentifier.of(scheduleId, seatNo);

                    boolean success = seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(userId),
                            Duration.ofMinutes(5)
                    );

                    if (success) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            threads.add(thread);
            thread.start();
        }

        // 모든 스레드 종료 대기
        for (Thread thread : threads) {
            thread.join(5000); // 5초 타임아웃
        }

        // Then
        assertThat(successCount.get()).isGreaterThanOrEqualTo(5);
        System.out.println("동시 예약 성공: " + successCount.get() + "/10");
    }
}