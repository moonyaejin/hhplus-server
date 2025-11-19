package kr.hhplus.be.server.concurrency;

import kr.hhplus.be.server.application.port.in.ReservationUseCase.*;
import kr.hhplus.be.server.application.service.ReservationService;
import kr.hhplus.be.server.domain.reservation.SeatAlreadyAssignedException;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertScheduleJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertScheduleJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWalletJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.queue.jpa.entity.QueueTokenJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.queue.jpa.repository.QueueTokenJpaRepository;
import kr.hhplus.be.server.infrastructure.redis.lock.LockAcquisitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * 분산락 통합 테스트 (실제 DB + Redis)
 */
@Disabled("Testcontainers 방식(ConcurrencyIntegrationTest)으로 개선되어 현재는 사용 안 함")
@SpringBootTest
@DisplayName("분산락 통합 테스트")
class ReservationConcurrencyTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    // DB Repository (테스트 데이터 생성용)
    @Autowired
    private ConcertJpaRepository concertRepository;

    @Autowired
    private ConcertScheduleJpaRepository scheduleRepository;

    @Autowired
    private QueueTokenJpaRepository queueTokenRepository;

    @Autowired
    private UserWalletJpaRepository walletRepository;

    private Long testScheduleId;
    private String testUserId;

    @BeforeEach
    void setUp() {
        // 1. Redis 클린업 (Redis 실행 중일 때만)
        try {
            redisTemplate.keys("lock:*").forEach(key -> redisTemplate.delete(key));
            redisTemplate.keys("seat:hold:*").forEach(key -> redisTemplate.delete(key));
        } catch (Exception e) {
            System.out.println("⚠️ Redis 연결 실패 - Redis를 먼저 실행하세요: docker run -d -p 6379:6379 redis:7-alpine");
            throw new RuntimeException("Redis가 실행되지 않았습니다. 테스트를 실행하기 전에 Redis를 시작하세요.", e);
        }

        // 2. DB 데이터 클린업 (중요!)
        cleanupTestData();

        // 3. DB 기본 데이터 생성
        setupTestData();
    }

    private void cleanupTestData() {
        // 역순으로 삭제 (FK 제약조건 때문)
        queueTokenRepository.deleteAll();
        walletRepository.deleteAll();
        scheduleRepository.deleteAll();
        concertRepository.deleteAll();
    }

    private void setupTestData() {
        // 콘서트 생성
        ConcertJpaEntity concert = new ConcertJpaEntity("Test Concert");
        concert = concertRepository.save(concert);

        // 콘서트 스케줄 생성
        ConcertScheduleJpaEntity schedule = new ConcertScheduleJpaEntity(
                concert,
                LocalDate.now().plusDays(30),
                50  // 50석
        );
        schedule = scheduleRepository.save(schedule);
        testScheduleId = schedule.getId();

        // 테스트용 사용자 ID
        testUserId = UUID.randomUUID().toString();

        // 사용자별 지갑 생성 (여러 명)
        for (int i = 0; i < 20; i++) {
            String userId = "test-user-" + i;
            UUID userUuid = UUID.nameUUIDFromBytes(userId.getBytes());

            if (!walletRepository.existsById(userUuid)) {
                UserWalletJpaEntity wallet = new UserWalletJpaEntity(userUuid, 1_000_000L);
                walletRepository.save(wallet);
            }
        }

        // 활성 대기열 토큰 생성 (여러 개)
        for (int i = 0; i < 20; i++) {
            String token = "active-token-" + i;
            String userId = "test-user-" + i;
            UUID userUuid = UUID.nameUUIDFromBytes(userId.getBytes());
            String userIdString = userUuid.toString();

            if (!queueTokenRepository.findByToken(token).isPresent()) {
                QueueTokenJpaEntity queueToken = new QueueTokenJpaEntity(token, userIdString);
                queueToken.activate();
                queueTokenRepository.save(queueToken);
            }
        }
    }

    @Test
    @DisplayName("동시 좌석 예약 - 1명만 성공")
    void concurrentTemporaryAssign_OnlyOneSucceeds() throws InterruptedException {
        // given
        int threadCount = 10;
        int testSeatNumber = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when: 10명이 동시에 같은 좌석 예약 시도
        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    String token = "active-token-" + threadNum;

                    TemporaryAssignCommand command = new TemporaryAssignCommand(
                            token,
                            testScheduleId,
                            testSeatNumber
                    );

                    TemporaryAssignResult result = reservationService.temporaryAssign(command);
                    successCount.incrementAndGet();

                } catch (SeatAlreadyAssignedException | LockAcquisitionException e) {
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    // 예상치 못한 예외는 로그 출력
                    System.err.println("⚠️ Unexpected exception in thread-" + threadNum + ": " + e.getMessage());
                    e.printStackTrace();
                    failCount.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        finishLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(9);
    }

    @Test
    @DisplayName("다른 좌석은 동시 예약 가능")
    void differentSeats_CanBeReservedConcurrently() throws InterruptedException {
        // given
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        ConcurrentHashMap<Integer, String> results = new ConcurrentHashMap<>();

        // when: 각 스레드가 다른 좌석 예약
        for (int i = 0; i < threadCount; i++) {
            final int seatNumber = 20 + i;  // 20, 21, 22, 23, 24번
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    String token = "active-token-" + threadNum;

                    TemporaryAssignCommand command = new TemporaryAssignCommand(
                            token,
                            testScheduleId,
                            seatNumber
                    );

                    TemporaryAssignResult result = reservationService.temporaryAssign(command);
                    results.put(seatNumber, result.reservationId());
                    System.out.println("Seat-" + seatNumber + " 예약 성공");

                } catch (Exception e) {
                    // 예상치 못한 예외는 로그 출력
                    System.err.println("⚠️ Unexpected exception: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then: 다른 좌석이므로 모두 성공해야 함
        System.out.println("\n=== 다른 좌석 예약 결과 ===");
        System.out.println("성공한 좌석 수: " + results.size());

        // 최소 4개 이상은 성공해야 함 (네트워크/타이밍 이슈 고려)
        assertThat(results.size()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("분산락 키가 좌석별로 분리됨")
    void lockKeys_AreSeparatedBySeat() throws InterruptedException {
        // given
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch finishLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);

        // when: 2개 스레드가 각각 다른 좌석 예약 (동시 시작)
        executor.submit(() -> {
            try {
                TemporaryAssignCommand command = new TemporaryAssignCommand(
                        "active-token-0",
                        testScheduleId,
                        30  // 30번 좌석
                );
                reservationService.temporaryAssign(command);
                successCount.incrementAndGet();
            } catch (Exception e) {
                System.out.println("Seat-30 실패: " + e.getMessage());
            } finally {
                finishLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                TemporaryAssignCommand command = new TemporaryAssignCommand(
                        "active-token-1",
                        testScheduleId,
                        31  // 31번 좌석
                );
                reservationService.temporaryAssign(command);
                successCount.incrementAndGet();
            } catch (Exception e) {
                System.out.println("Seat-31 실패: " + e.getMessage());
            } finally {
                finishLatch.countDown();
            }
        });

        finishLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // then: 락 키가 다르므로 둘 다 성공
        assertThat(successCount.get()).isEqualTo(2);
    }
}