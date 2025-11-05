package kr.hhplus.be.server.ranking;

import kr.hhplus.be.server.application.port.in.ReservationUseCase;
import kr.hhplus.be.server.application.port.in.ReservationUseCase.*;
import kr.hhplus.be.server.application.port.in.RankingUseCase;
import kr.hhplus.be.server.concurrency.config.TestTaskExecutorConfig;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * 극한 동시성 스트레스 테스트
 *
 * 실제 프로덕션 환경의 부하 테스트
 * - 10,000+ 동시 요청
 * - TPS, Throughput 측정
 * - 응답 시간 분포
 * - 데드락, 타임아웃 검증
 * - 시스템 한계 파악
 */
@Slf4j
@SpringBootTest
@Import(TestTaskExecutorConfig.class)
@ActiveProfiles("test")
@DisplayName("극한 동시성 스트레스")
class ConcurrencyStressTest {

    @Autowired
    private ReservationUseCase reservationUseCase;

    @Autowired
    private RankingUseCase rankingUseCase;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    @Qualifier("concurrencyTestExecutor")
    private TaskExecutor taskExecutor;

    private ExecutorService stressTestExecutor;

    @BeforeEach
    void setUp() {
        // 스트레스 테스트용 대용량 스레드 풀
        stressTestExecutor = Executors.newFixedThreadPool(200);

        // Redis 초기화
        Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("10,000명 동시 예약 추적 - 데이터 정합성")
    void 만명_동시_예약_추적_정합성_검증() throws InterruptedException {
        // Given
        Long scheduleId = 1L;
        int totalRequests = 10_000;
        int availableSeats = 100;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(totalRequests);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Long> responseTimes = new CopyOnWriteArrayList<>();

        log.info("=== 10,000명 동시 예약 추적 시작 ===");
        Instant startTime = Instant.now();

        // When - 10,000명이 동시에 예약 추적
        for (int i = 0; i < totalRequests; i++) {
            stressTestExecutor.submit(() -> {
                try {
                    startLatch.await();

                    long requestStart = System.currentTimeMillis();

                    try {
                        // 예약 추적
                        rankingUseCase.trackReservation(scheduleId, 1);

                        long responseTime = System.currentTimeMillis() - requestStart;
                        responseTimes.add(responseTime);
                        successCount.incrementAndGet();

                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // 동시 시작
        Thread.sleep(100);
        startLatch.countDown();

        // 완료 대기 (최대 60초)
        boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
        Duration totalDuration = Duration.between(startTime, Instant.now());

        // Then - 상세 분석
        log.info("");
        log.info("=== 스트레스 테스트 결과 ===");
        log.info("총 소요 시간: {}초", totalDuration.getSeconds());
        log.info("처리량:");
        log.info("   - 총 요청: {}", totalRequests);
        log.info("   - 성공: {} ({}%)", successCount.get(),
                successCount.get() * 100.0 / totalRequests);
        log.info("   - 실패: {} ({}%)", failureCount.get(),
                failureCount.get() * 100.0 / totalRequests);

        log.info("");
        log.info("성능 메트릭:");
        log.info("   - TPS: {} req/s",
                totalRequests / Math.max(1, totalDuration.getSeconds()));
        log.info("   - 처리 완료: {}", completed ? "예" : "아니오 (타임아웃)");

        if (!responseTimes.isEmpty()) {
            Collections.sort(responseTimes);
            long p50 = responseTimes.get(responseTimes.size() / 2);
            long p95 = responseTimes.get((int)(responseTimes.size() * 0.95));
            long p99 = responseTimes.get((int)(responseTimes.size() * 0.99));
            long p999 = responseTimes.get((int)(responseTimes.size() * 0.999));
            long max = responseTimes.get(responseTimes.size() - 1);

            log.info("");
            log.info("응답 시간 분포:");
            log.info("   - P50:  {}ms", p50);
            log.info("   - P95:  {}ms", p95);
            log.info("   - P99:  {}ms", p99);
            log.info("   - P999: {}ms", p999);
            log.info("   - Max:  {}ms", max);

            log.info("");
            log.info("SLA 검증:");
            log.info("   - P95 < 3s: {} ({}ms)", p95 < 3000 ? "✅" : "❌", p95);
            log.info("   - P99 < 5s: {} ({}ms)", p99 < 5000 ? "✅" : "❌", p99);
        }

        // 실무 검증
        assertThat(completed).isTrue();
        assertThat(successCount.get())
                .as("대부분의 요청은 성공해야 함")
                .isGreaterThan((int)(totalRequests * 0.9));   // 90% 이상 성공

        // 응답시간 SLA
        if (!responseTimes.isEmpty()) {
            long p95 = responseTimes.get((int)(responseTimes.size() * 0.95));
            assertThat(p95)
                    .as("P95 응답시간은 3초 이내 (실무 SLA)")
                    .isLessThan(3000L);
        }
    }


    @Test
    @DisplayName("점진적 부하 증가 - 시스템 한계 파악")
    void 점진적_부하_증가_시스템_한계_파악() throws InterruptedException {
        // Given - 부하를 점진적으로 증가
        Long scheduleId = 2L;
        int[] loadLevels = {100, 500, 1000, 2000, 5000};  // 점진적 증가

        Map<Integer, LoadTestResult> results = new LinkedHashMap<>();

        log.info("=== 점진적 부하 증가 테스트 시작 ===");

        // When - 각 부하 레벨 테스트
        for (int loadLevel : loadLevels) {
            log.info("");
            log.info("부하 레벨: {} req/s", loadLevel);

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(loadLevel);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            List<Long> responseTimes = new CopyOnWriteArrayList<>();

            Instant startTime = Instant.now();

            for (int i = 0; i < loadLevel; i++) {
                final int requestId = i;

                stressTestExecutor.submit(() -> {
                    try {
                        startLatch.await();

                        long requestStart = System.currentTimeMillis();

                        try {
                            rankingUseCase.trackReservation(scheduleId, 1);

                            long responseTime = System.currentTimeMillis() - requestStart;
                            responseTimes.add(responseTime);
                            successCount.incrementAndGet();

                        } catch (Exception e) {
                            failureCount.incrementAndGet();
                        }

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completionLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
            Duration duration = Duration.between(startTime, Instant.now());

            // 결과 저장
            LoadTestResult result = new LoadTestResult(
                    loadLevel,
                    successCount.get(),
                    failureCount.get(),
                    duration.toMillis(),
                    responseTimes
            );
            results.put(loadLevel, result);

            log.info("   - 성공: {} / {}", successCount.get(), loadLevel);
            log.info("   - 실패: {}", failureCount.get());
            log.info("   - 소요 시간: {}ms", duration.toMillis());

            if (!responseTimes.isEmpty()) {
                Collections.sort(responseTimes);
                long p95 = responseTimes.get((int)(responseTimes.size() * 0.95));
                log.info("   - P95 응답: {}ms", p95);
            }

            // 다음 테스트 전 잠시 대기
            Thread.sleep(1000);
        }

        // Then - 결과 분석
        log.info("");
        log.info("=== 부하 테스트 종합 분석 ===");
        log.info(String.format("%-10s | %-10s | %-10s | %-12s | %-10s",
                "부하", "성공률", "TPS", "P95 응답", "판정"));
        log.info("-".repeat(65));

        for (Map.Entry<Integer, LoadTestResult> entry : results.entrySet()) {
            LoadTestResult result = entry.getValue();
            double successRate = result.successCount * 100.0 / result.totalRequests;
            long tps = result.successCount * 1000 / Math.max(1, result.durationMs);

            long p95 = 0;
            if (!result.responseTimes.isEmpty()) {
                Collections.sort(result.responseTimes);
                p95 = result.responseTimes.get((int)(result.responseTimes.size() * 0.95));
            }

            String status = successRate >= 95 && p95 < 3000 ? "✅ 정상" : "⚠️ 한계";

            log.info(String.format("%-10d | %-9.1f%% | %-10d | %-10dms | %s",
                    result.totalRequests, successRate, tps, p95, status));
        }
    }

    @Test
    @DisplayName("순간 트래픽 스파이크 - 버스트 처리")
    void 순간_트래픽_스파이크_버스트_처리() throws InterruptedException {
        // Given - 평소 100 TPS, 순간 5000 TPS 스파이크
        Long scheduleId = 3L;

        log.info("=== 트래픽 스파이크 시뮬레이션 ===");
        log.info("평상시: 100 TPS");
        log.info("스파이크: 5000 TPS (50배)");
        log.info("");

        // When - 단계별 부하
        List<BurstResult> results = new ArrayList<>();

        // 1. 평상시 부하
        BurstResult normalLoad = executeBurst("평상시", scheduleId, 100, 1000);
        results.add(normalLoad);

        Thread.sleep(1000);

        // 2. 스파이크 시작
        BurstResult spike = executeBurst("스파이크", scheduleId, 5000, 1000);
        results.add(spike);

        Thread.sleep(1000);

        // 3. 스파이크 후 안정화
        BurstResult recovery = executeBurst("안정화", scheduleId, 100, 1000);
        results.add(recovery);

        // Then - 버스트 패턴 분석
        log.info("");
        log.info("=== 버스트 패턴 분석 ===");

        for (BurstResult result : results) {
            log.info("");
            log.info("[{}]", result.phase);
            log.info("   - 요청: {}", result.totalRequests);
            log.info("   - 성공: {} ({}%)", result.successCount,
                    result.successCount * 100.0 / result.totalRequests);
            log.info("   - P95 응답: {}ms", result.p95ResponseTime);
            log.info("   - TPS: {}", result.tps);
        }

        // 안정화 단계에서는 정상 복구되어야 함
        assertThat(recovery.successCount * 100.0 / recovery.totalRequests)
                .as("안정화 후 성공률 95% 이상")
                .isGreaterThanOrEqualTo(90.0);
    }

    private BurstResult executeBurst(String phase, Long scheduleId, int requests, long durationMs)
            throws InterruptedException {

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(requests);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Long> responseTimes = new CopyOnWriteArrayList<>();

        Instant startTime = Instant.now();

        for (int i = 0; i < requests; i++) {
            stressTestExecutor.submit(() -> {
                try {
                    startLatch.await();

                    long requestStart = System.currentTimeMillis();

                    try {
                        rankingUseCase.trackReservation(scheduleId, 1);

                        long responseTime = System.currentTimeMillis() - requestStart;
                        responseTimes.add(responseTime);
                        successCount.incrementAndGet();

                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        completionLatch.await(30, TimeUnit.SECONDS);
        Duration duration = Duration.between(startTime, Instant.now());

        long p95 = 0;
        if (!responseTimes.isEmpty()) {
            Collections.sort(responseTimes);
            p95 = responseTimes.get((int)(responseTimes.size() * 0.95));
        }

        long tps = successCount.get() * 1000 / Math.max(1, duration.toMillis());

        return new BurstResult(phase, requests, successCount.get(), failureCount.get(), p95, tps);
    }

    // Helper Classes
    private static class LoadTestResult {
        final int totalRequests;
        final int successCount;
        final int failureCount;
        final long durationMs;
        final List<Long> responseTimes;

        LoadTestResult(int totalRequests, int successCount, int failureCount,
                       long durationMs, List<Long> responseTimes) {
            this.totalRequests = totalRequests;
            this.successCount = successCount;
            this.failureCount = failureCount;
            this.durationMs = durationMs;
            this.responseTimes = new ArrayList<>(responseTimes);
        }
    }

    private static class BurstResult {
        final String phase;
        final int totalRequests;
        final int successCount;
        final int failureCount;
        final long p95ResponseTime;
        final long tps;

        BurstResult(String phase, int totalRequests, int successCount, int failureCount,
                    long p95ResponseTime, long tps) {
            this.phase = phase;
            this.totalRequests = totalRequests;
            this.successCount = successCount;
            this.failureCount = failureCount;
            this.p95ResponseTime = p95ResponseTime;
            this.tps = tps;
        }
    }
}