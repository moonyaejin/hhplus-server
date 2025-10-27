package kr.hhplus.be.server.infrastructure.redis.lock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class RedisDistributedLockTest {

    @Autowired
    private RedisDistributedLock distributedLock;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final String TEST_LOCK_KEY = "test:lock:";

    @BeforeEach
    void setUp() {
        // 테스트 전 Redis 클린업
        redisTemplate.keys(TEST_LOCK_KEY + "*")
                .forEach(key -> redisTemplate.delete(key));
    }

    @AfterEach
    void tearDown() {
        // 테스트 후 Redis 클린업
        redisTemplate.keys(TEST_LOCK_KEY + "*")
                .forEach(key -> redisTemplate.delete(key));
    }

    @Test
    @DisplayName("락 획득 및 해제 - 기본 동작")
    void basicLockAndUnlock() {
        // given
        String lockKey = TEST_LOCK_KEY + "basic";

        // when
        String result = distributedLock.executeWithLock(
                lockKey,
                5L,      // 5초 TTL
                1,       // 재시도 없음
                0L,
                () -> "success"
        );

        // then
        assertThat(result).isEqualTo("success");

        // 락이 해제되었는지 확인
        String lockValue = redisTemplate.opsForValue().get(lockKey);
        assertThat(lockValue).isNull();
    }

    @Test
    @DisplayName("동시 요청 - 한 번에 하나만 실행")
    void concurrentAccess() throws InterruptedException {
        // given
        String lockKey = TEST_LOCK_KEY + "concurrent";
        int threadCount = 10;
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // when: 10개 스레드가 동시에 락 획득 시도
        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    System.out.println("Thread-" + threadNum + " 시작");

                    distributedLock.executeWithLock(
                            lockKey,
                            3L,
                            1,      // ⭐ 재시도 없음 (바로 실패)
                            0L,
                            () -> {
                                System.out.println("Thread-" + threadNum + " 락 획득 성공!");
                                sleep(1000);
                                successCount.incrementAndGet();
                                return null;
                            }
                    );
                } catch (LockAcquisitionException e) {
                    System.out.println("Thread-" + threadNum + " 락 획득 실패! (LockAcquisitionException)");
                    failCount.incrementAndGet();
                } catch (InterruptedException e) {
                    System.out.println("Thread-" + threadNum + " 인터럽트 발생!");  // ⭐ 로그 추가
                    failCount.incrementAndGet();  // ⭐ 카운트 추가
                    Thread.currentThread().interrupt();
                } catch (Exception e) {  // ⭐ 모든 예외 잡기
                    System.out.println("Thread-" + threadNum + " 예외 발생: " + e.getClass().getName() + " - " + e.getMessage());
                    e.printStackTrace();
                    failCount.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        readyLatch.await();
        System.out.println("모든 스레드 준비 완료");
        startLatch.countDown();
        System.out.println("시작 신호 발송!");
        finishLatch.await();
        System.out.println("모든 스레드 완료");
        executor.shutdown();

        // then
        System.out.println("=== 최종 결과 ===");
        System.out.println("Success: " + successCount.get());
        System.out.println("Fail: " + failCount.get());
        System.out.println("Total: " + (successCount.get() + failCount.get()));  // ⭐ 합계 확인

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(9);
    }

    @Test
    @DisplayName("Retry 로직 - 재시도 후 성공")
    void retryMechanism() throws InterruptedException {
        // given
        String lockKey = TEST_LOCK_KEY + "retry";

        // 먼저 락 획득
        distributedLock.executeWithLock(
                lockKey,
                1L,  // 1초 TTL
                1,
                0L,
                () -> {
                    sleep(500);  // 0.5초 작업
                    return null;
                }
        );

        // when: 0.5초 대기 후 재시도 (락이 아직 있음)
        // 하지만 retry를 통해 성공
        String result = distributedLock.executeWithLock(
                lockKey,
                5L,
                3,      // 3번 재시도
                600L,   // 0.6초 대기 (첫 번째 락이 만료될 시간)
                () -> "success"
        );

        // then
        assertThat(result).isEqualTo("success");
    }

    @Test
    @DisplayName("예외 발생 시에도 락 해제")
    void lockReleasedOnException() {
        // given
        String lockKey = TEST_LOCK_KEY + "exception";

        // when & then
        assertThatThrownBy(() ->
                distributedLock.executeWithLock(
                        lockKey,
                        5L,
                        1,
                        0L,
                        () -> {
                            throw new RuntimeException("작업 중 예외 발생");
                        }
                )
        ).isInstanceOf(RuntimeException.class);

        // 예외가 발생해도 락은 해제되어야 함
        String lockValue = redisTemplate.opsForValue().get(lockKey);
        assertThat(lockValue).isNull();
    }

    @Test
    @DisplayName("TTL 만료 후 자동 해제")
    void ttlExpiration() throws InterruptedException {
        // given
        String lockKey = TEST_LOCK_KEY + "ttl";
        String value = "test-value";

        // when: 1초 TTL로 락 획득
        distributedLock.tryLock(lockKey, value, 1L);

        // then: 즉시 확인 - 락 존재
        assertThat(redisTemplate.opsForValue().get(lockKey)).isEqualTo(value);

        // when: 2초 대기 (TTL 만료)
        Thread.sleep(2000);

        // then: 락이 자동 해제됨
        assertThat(redisTemplate.opsForValue().get(lockKey)).isNull();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}