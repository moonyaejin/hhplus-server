package kr.hhplus.be.server.queue;

import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.domain.queue.model.QueueToken;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Redis 대기열 통합 테스트
 *
 * 주요 검증 사항:
 * 1. 기본 토큰 발급/조회 기능
 * 2. 100명 제한 동시성 제어
 * 3. 토큰 만료 및 재활성화
 * 4. 대기열 순서 관리
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisQueueTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379).toString());
    }

    @Autowired
    private QueuePort queuePort;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void setUp() {
        // Redis 완전 초기화
        redisTemplate.getConnectionFactory().getConnection().flushDb();

        // Redis 처리 완료 대기
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Nested
    @DisplayName("기본 토큰 발급 테스트")
    class BasicTokenIssueTest {

        @Test
        @Order(1)
        @DisplayName("토큰을 발급하면 null이 아닌 값을 반환한다")
        void issueToken() {
            // given
            String userId = "user-001";

            // when
            QueueToken token = queuePort.issue(userId);

            // then
            assertThat(token).isNotNull();
            assertThat(token.value()).isNotBlank();
            log.info("토큰 발급 성공: {}", token.value());
        }

        @Test
        @Order(2)
        @DisplayName("같은 사용자가 재발급 요청하면 동일한 토큰을 반환한다")
        void returnSameTokenForSameUser() {
            // given
            String userId = "user-001";
            QueueToken firstToken = queuePort.issue(userId);

            // when
            QueueToken secondToken = queuePort.issue(userId);

            // then
            assertThat(firstToken.value()).isEqualTo(secondToken.value());
            log.info("재발급 토큰 일치 확인: {}", firstToken.value());
        }
    }

    @Nested
    @DisplayName("활성화 규칙 테스트")
    class ActivationRuleTest {

        @Test
        @DisplayName("활성 사용자가 100명 미만이면 즉시 활성화된다")
        void activateWhenUnder100() {
            // given
            String userId = "user-001";

            // when
            QueueToken token = queuePort.issue(userId);

            // then
            assertThat(queuePort.isActive(token.value())).isTrue();
            assertThat(queuePort.getActiveCount()).isEqualTo(1L);
            assertThat(queuePort.getWaitingCount()).isEqualTo(0L);

            log.info("즉시 활성화 확인 - token: {}, activeCount: {}",
                    token.value(), queuePort.getActiveCount());
        }

        @Test
        @DisplayName("활성 사용자가 100명이면 대기열에 추가된다")
        void waitingWhenAt100() {
            // given - 100명 먼저 활성화
            for (int i = 1; i <= 100; i++) {
                queuePort.issue("user-" + i);
            }
            assertThat(queuePort.getActiveCount()).isEqualTo(100L);

            // when - 101번째 사용자 발급
            QueueToken token = queuePort.issue("user-101");

            // then
            assertThat(queuePort.isActive(token.value())).isFalse();
            assertThat(queuePort.getActiveCount()).isEqualTo(100L);
            assertThat(queuePort.getWaitingCount()).isEqualTo(1L);

            log.info("대기열 추가 확인 - waiting token: {}, waitingCount: {}",
                    token.value(), queuePort.getWaitingCount());
        }

        @Test
        @DisplayName("대기 순번을 정확하게 조회할 수 있다")
        void getWaitingPosition() {
            // given - 100명 활성화
            for (int i = 1; i <= 100; i++) {
                queuePort.issue("active-" + i);
            }

            // when - 대기열에 2명 추가
            QueueToken token1 = queuePort.issue("waiting-1");
            QueueToken token2 = queuePort.issue("waiting-2");

            // then
            Long position1 = queuePort.getWaitingPosition(token1.value());
            Long position2 = queuePort.getWaitingPosition(token2.value());

            assertThat(position1).isEqualTo(1L);
            assertThat(position2).isEqualTo(2L);

            log.info("대기 순번 확인 - token1: {}번, token2: {}번", position1, position2);
        }
    }

    @Nested
    @DisplayName("토큰 만료 및 재활성화 테스트")
    class TokenExpirationTest {

        @Test
        @DisplayName("토큰을 만료시키면 활성 상태가 해제된다")
        void expireToken() {
            // given
            QueueToken token = queuePort.issue("user-001");
            assertThat(queuePort.isActive(token.value())).isTrue();
            Long beforeCount = queuePort.getActiveCount();

            // when
            queuePort.expire(token.value());

            // then
            assertThat(queuePort.isActive(token.value())).isFalse();
            assertThat(queuePort.getActiveCount()).isEqualTo(beforeCount - 1);

            log.info("토큰 만료 확인 - token: {}, 만료 전: {}, 만료 후: {}",
                    token.value(), beforeCount, queuePort.getActiveCount());
        }

        @Test
        @DisplayName("토큰 만료 후 새 사용자가 활성화될 수 있다")
        void newUserCanActivateAfterExpire() {
            // given - 100명 활성화
            List<QueueToken> activeTokens = new ArrayList<>();
            for (int i = 1; i <= 100; i++) {
                activeTokens.add(queuePort.issue("user-" + i));
            }

            // 101번째는 대기
            QueueToken waitingToken = queuePort.issue("waiting-user");
            assertThat(queuePort.isActive(waitingToken.value())).isFalse();

            // when - 1번 사용자 만료
            queuePort.expire(activeTokens.get(0).value());

            // then - 새 사용자가 활성화 가능
            QueueToken newToken = queuePort.issue("new-user");
            assertThat(queuePort.isActive(newToken.value())).isTrue();
            assertThat(queuePort.getActiveCount()).isEqualTo(100L);

            log.info("만료 후 재활성화 확인 - 새 토큰: {}, activeCount: {}",
                    newToken.value(), queuePort.getActiveCount());
        }
    }

    @Nested
    @DisplayName("동시성 제어 테스트")
    class ConcurrencyTest {

        @Test
        @DisplayName("여러 명이 동시에 토큰을 발급받아도 정확히 100명만 활성화된다")
        void concurrentIssue() throws InterruptedException {
            // given
            int totalUsers = 150;
            int threadPoolSize = 50;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(totalUsers);
            ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            // when - 150명 동시 요청
            long startTime = System.currentTimeMillis();

            for (int i = 1; i <= totalUsers; i++) {
                String userId = "user-" + i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        QueueToken token = queuePort.issue(userId);

                        if (queuePort.isActive(token.value())) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        log.error("토큰 발급 실패: userId={}", userId, e);
                        failCount.incrementAndGet();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // 동시 시작
            boolean completed = endLatch.await(15, TimeUnit.SECONDS);
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            long endTime = System.currentTimeMillis();

            // Redis 처리 완료 대기
            Thread.sleep(500);

            // then
            assertThat(completed).isTrue();

            Long activeCount = queuePort.getActiveCount();
            Long waitingCount = queuePort.getWaitingCount();

            log.info("=== 동시성 테스트 결과 ===");
            log.info("총 요청: {}명", totalUsers);
            log.info("스레드 풀: {}개", threadPoolSize);
            log.info("소요 시간: {}ms", endTime - startTime);
            log.info("활성화 성공: {}명 (스레드 카운트: {})", activeCount, successCount.get());
            log.info("대기열 추가: {}명 (스레드 카운트: {})", waitingCount, failCount.get());
            log.info("========================");

            assertThat(activeCount).isEqualTo(100L);
            assertThat(waitingCount).isEqualTo(50L);
            assertThat(activeCount + waitingCount).isEqualTo((long) totalUsers);
        }

        @Test
        @DisplayName("동시 만료와 신규 발급이 있어도 100명 제한이 유지된다")
        void concurrentExpireAndIssue() throws InterruptedException {
            // given - 먼저 100명 활성화
            List<QueueToken> tokens = new ArrayList<>();
            for (int i = 1; i <= 100; i++) {
                tokens.add(queuePort.issue("initial-" + i));
            }

            int expireCount = 30;
            int newIssueCount = 50;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(expireCount + newIssueCount);
            ExecutorService executor = Executors.newFixedThreadPool(20);

            // when - 30명 만료 + 50명 신규 발급 동시 진행
            // 만료 작업
            for (int i = 0; i < expireCount; i++) {
                int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        queuePort.expire(tokens.get(index).value());
                    } catch (Exception e) {
                        log.error("만료 실패", e);
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            // 신규 발급 작업
            for (int i = 1; i <= newIssueCount; i++) {
                String userId = "new-user-" + i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        queuePort.issue(userId);
                    } catch (Exception e) {
                        log.error("발급 실패", e);
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = endLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            Thread.sleep(500);

            // then
            assertThat(completed).isTrue();

            Long activeCount = queuePort.getActiveCount();
            Long waitingCount = queuePort.getWaitingCount();

            log.info("=== 혼합 동시성 테스트 결과 ===");
            log.info("초기 활성: 100명");
            log.info("만료: {}명", expireCount);
            log.info("신규 발급: {}명", newIssueCount);
            log.info("최종 활성: {}명", activeCount);
            log.info("최종 대기: {}명", waitingCount);
            log.info("============================");

            // 100명 - 30명(만료) + 30명(신규 활성) = 100명 유지
            assertThat(activeCount).isLessThanOrEqualTo(100L);
            assertThat(activeCount).isGreaterThan(0L);
        }
    }

    @Nested
    @DisplayName("엣지 케이스 테스트")
    class EdgeCaseTest {

        @Test
        @DisplayName("존재하지 않는 토큰 조회 시 적절히 처리된다")
        void handleNonExistentToken() {
            // given
            String nonExistentToken = "non-existent-token";

            // when & then
            assertThat(queuePort.isActive(nonExistentToken)).isFalse();
            assertThat(queuePort.getWaitingPosition(nonExistentToken)).isNull();
            assertThat(queuePort.userIdOf(nonExistentToken)).isNull();

            // 만료해도 예외 발생하지 않음
            assertThatCode(() -> queuePort.expire(nonExistentToken))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("동일한 토큰을 여러 번 만료해도 카운터가 음수가 되지 않는다")
        void expireTokenMultipleTimes() {
            // given
            QueueToken token = queuePort.issue("user-001");
            assertThat(queuePort.getActiveCount()).isEqualTo(1L);

            // when - 같은 토큰 3번 만료
            queuePort.expire(token.value());
            queuePort.expire(token.value());
            queuePort.expire(token.value());

            // then
            Long activeCount = queuePort.getActiveCount();
            assertThat(activeCount).isGreaterThanOrEqualTo(0L);

            log.info("중복 만료 후 activeCount: {}", activeCount);
        }
    }
}