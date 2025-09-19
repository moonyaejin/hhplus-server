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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        // 테스트 전 데이터 정리
        seatHoldRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        // 테스트 후 데이터 정리
        seatHoldRepository.deleteAll();
    }

    /**
     * 시나리오: 콘서트 티켓 오픈 시 인기 좌석에 대한 동시 예약 상황

     * Given: 10명의 사용자가 준비
     * When: 모두가 동시에 같은 좌석(15번)을 예약 시도
     * Then: 오직 1명만 성공하고 나머지 9명은 실패

     * 검증: Race Condition 방지 및 데이터 일관성 보장
     */

    @Test
    @DisplayName("동시에 여러 유저가 같은 좌석 예약 시 한 명만 성공")
    void onlyOneUserCanReserveSameSeat() throws InterruptedException {
        // Given
        SeatIdentifier seat = new SeatIdentifier(
                new ConcertScheduleId(1L),
                new SeatNumber(15)
        );

        // 먼저 단일 호출 테스트 (UUID 사용)
        try {
            String testUserId = UUID.randomUUID().toString();  // UUID 생성
            boolean testResult = seatHoldPort.tryHold(
                    seat,
                    UserId.ofString(testUserId),
                    Duration.ofMinutes(5)
            );
            System.out.println("단일 호출 테스트 결과: " + testResult);

            // 테스트 후 정리
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
            final String userId = UUID.randomUUID().toString();  // 각각 UUID 생성
            executor.execute(() -> {
                try {
                    startLatch.await();

                    boolean success = seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(userId),  // UUID 형식
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

    /**
     * 시나리오: 정상적인 예약 프로세스에서 서로 다른 좌석 선택

     * Given: 10명의 사용자가 각각 다른 좌석 선택
     * When: 동시에 예약 요청
     * Then: 모든 사용자가 성공적으로 예약

     * 검증: 불필요한 락킹이 없고 정상적인 동시 처리 가능
     */

    @Test
    @DirtiesContext
    @DisplayName("서로 다른 좌석은 동시에 예약 가능")
    void differentSeatsCanBeReservedConcurrently() throws InterruptedException {
        // Given
        int seatCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(seatCount);
        AtomicInteger successCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(seatCount);

        // When
        for (int i = 1; i <= seatCount; i++) {
            final int seatNo = i;
            final String userId = UUID.randomUUID().toString();  // UUID 사용

            executor.execute(() -> {
                try {
                    startLatch.await();

                    SeatIdentifier seat = new SeatIdentifier(
                            new ConcertScheduleId(10L),
                            new SeatNumber(seatNo)
                    );

                    boolean success = seatHoldPort.tryHold(
                            seat,
                            UserId.ofString(userId),  // UUID 형식
                            Duration.ofMinutes(5)
                    );

                    if (success) {
                        int count = successCount.incrementAndGet();
                        System.out.println("Seat " + seatNo + " reserved by " +
                                userId.substring(0, 8) + " (total: " + count + ")");
                    } else {
                        System.out.println("Seat " + seatNo + " reservation failed");
                    }
                } catch (Exception e) {
                    System.err.println("Seat " + seatNo + " error: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await();
        executor.shutdown();


        System.out.println("최종 성공 개수: " + successCount.get());
        assertThat(successCount.get()).isEqualTo(seatCount);
    }
}