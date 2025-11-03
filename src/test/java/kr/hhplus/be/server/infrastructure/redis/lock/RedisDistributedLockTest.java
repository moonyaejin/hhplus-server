package kr.hhplus.be.server.infrastructure.redis.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * RedisDistributedLock 단위 테스트
 */
@SpringBootTest
@DisplayName("RedisDistributedLockTest")
class RedisDistributedLockTest {

    private static final String TEST_LOCK_KEY = "test:lock:";

    @Autowired
    private RedisDistributedLock distributedLock;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void setUp() {
        // 테스트 전 Redis 클린업
        redisTemplate.keys(TEST_LOCK_KEY + "*")
                .forEach(key -> redisTemplate.delete(key));
    }

    @Test
    @DisplayName("락 획득 및 해제 - 기본 동작")
    void basicLockAndUnlock() throws InterruptedException {
        // given
        String lockKey = TEST_LOCK_KEY + "basic";

        // when: 짧은 TTL로 작업 실행
        String result = distributedLock.executeWithLock(
                lockKey,
                1L,      // 1초 TTL (테스트용)
                1,       // 재시도 없음
                0L,
                () -> "success"
        );

        // then: 작업 성공
        assertThat(result).isEqualTo("success");

        // TTL로 자동 만료되기 때문에, 짧은 시간 이후 락이 없어야 함
        // Note: 예전에는 명시적 unlock 호출이 있었지만,
        // race condition을 피하기 위해 TTL로만 관리하는 방식으로 변경됨
        Thread.sleep(1500); // TTL(1초) + 여유
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

        // when: 10개 스레드가 동시에 락 획득 시도
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    distributedLock.executeWithLock(
                            lockKey,
                            5L,
                            1,   // 재시도 없음 (충돌 확인용)
                            0L,
                            () -> {
                                successCount.incrementAndGet();
                                sleep(100); // 작업 시간
                                return null;
                            }
                    );
                } catch (LockAcquisitionException e) {
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    finishLatch.countDown();
                }
            }).start();
        }

        readyLatch.await();
        startLatch.countDown();
        finishLatch.await(10, TimeUnit.SECONDS);

        // then: 한 번에 하나씩만 실행되어야 함
        // retry가 없으므로 1개만 성공
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(9);
    }

    @Test
    @DisplayName("재시도 메커니즘 - 락 획득 성공")
    void retryMechanism() throws InterruptedException {
        // given
        String lockKey = TEST_LOCK_KEY + "retry";

        // 먼저 락을 잡아둠 (0.5초 TTL)
        distributedLock.executeWithLock(
                lockKey,
                1L,  // 0.5초보다 긴 TTL
                1,
                0L,
                () -> {
                    sleep(500);
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
    void lockReleasedOnException() throws InterruptedException {
        // given
        String lockKey = TEST_LOCK_KEY + "exception";

        // when & then: 예외 발생
        assertThatThrownBy(() ->
                distributedLock.executeWithLock(
                        lockKey,
                        1L,  // 1초 TTL
                        1,
                        0L,
                        () -> {
                            throw new RuntimeException("작업 중 예외 발생");
                        }
                )
        ).isInstanceOf(RuntimeException.class);

        // 예외가 발생해도 TTL로 락은 자동 만료됨
        Thread.sleep(1500);
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