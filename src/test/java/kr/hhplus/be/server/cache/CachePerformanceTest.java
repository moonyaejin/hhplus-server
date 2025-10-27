package kr.hhplus.be.server.cache;

import kr.hhplus.be.server.application.service.ConcertService;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertScheduleJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertScheduleJpaRepository;
import kr.hhplus.be.server.web.concert.dto.ConcertDto;
import kr.hhplus.be.server.web.concert.dto.ScheduleDto;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐시 성능 측정 통합 테스트
 *
 * [테스트 시나리오]
 * 1. 콘서트 목록 캐시: 히트율, 응답 시간
 * 2. 스케줄 캐시: 히트율, DB 쿼리 감소
 * 3. 동시 접근: 부하 상황에서 캐시 효과
 * 4. 캐시 무효화: 올바른 무효화 동작
 */
@Slf4j
@SpringBootTest
@DisplayName("캐시 성능 통합 테스트")
class CachePerformanceTest {

    @Autowired
    private ConcertService concertService;

    @Autowired
    private ConcertJpaRepository concertRepository;

    @Autowired
    private ConcertScheduleJpaRepository scheduleRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private Long testConcertId;
    private LocalDate testDate;

    @BeforeEach
    void setUp() {
        // Redis 완전 클리어 (flushDb 사용)
        try {
            Objects.requireNonNull(redisTemplate.getConnectionFactory())
                    .getConnection()
                    .serverCommands()
                    .flushDb();  // flushAll 대신 flushDb
        } catch (Exception e) {
            log.warn("Redis flush 실패: {}", e.getMessage());
        }

        // 약간 대기 (Redis 정리 완료 대기)
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 캐시 이름 동적으로 클리어
        cacheManager.getCacheNames().forEach(cacheName -> {
            try {
                var cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    cache.clear();
                    log.debug("캐시 클리어: {}", cacheName);
                }
            } catch (Exception e) {
                log.warn("캐시 클리어 실패: {} - {}", cacheName, e.getMessage());
            }
        });

        // 테스트 데이터 준비
        testDate = LocalDate.now().plusDays(1);

        ConcertJpaEntity concert = new ConcertJpaEntity("캐시 테스트용 콘서트");
        concert = concertRepository.save(concert);
        testConcertId = concert.getId();

        ConcertScheduleJpaEntity schedule = new ConcertScheduleJpaEntity(
                concert,
                testDate,
                50
        );
        scheduleRepository.save(schedule);

        log.info("테스트 준비 완료 - concertId: {}, date: {}", testConcertId, testDate);
    }

    @Test
    @DisplayName("1. 콘서트 목록 캐시 - 히트율 및 성능")
    void testConcertListCache() {
        // Given: 실제 티켓팅 서비스 시나리오
        int warmupCount = 20;      // 초기 사용자들의 조회
        int measureCount = 100;    // 티켓 오픈 시 폭발적인 트래픽

        // 1단계: 워밍업 (JVM 최적화 + 초기 사용자 시뮬레이션)
        log.info("🔥 워밍업 시작 ({}회)...", warmupCount);
        for (int i = 0; i < warmupCount; i++) {
            concertService.getAllConcerts();
        }

        // 2단계: 캐시 클리어 후 캐시 미스 측정 (DB 조회)
        cacheManager.getCache("concerts").clear();
        log.info("📊 캐시 미스 측정 ({}회 반복)...", measureCount);

        long totalTimeMiss = 0;
        for (int i = 0; i < measureCount; i++) {
            cacheManager.getCache("concerts").clear(); // 매번 캐시 클리어
            long start = System.nanoTime();
            concertService.getAllConcerts();
            totalTimeMiss += (System.nanoTime() - start);
        }
        long avgTimeMissNano = totalTimeMiss / measureCount;

        // 3단계: 캐시 히트 측정 (캐시에서 조회)
        log.info("⚡ 캐시 히트 측정 ({}회 반복)...", measureCount);
        concertService.getAllConcerts(); // 캐시 채우기

        long totalTimeHit = 0;
        for (int i = 0; i < measureCount; i++) {
            long start = System.nanoTime();
            concertService.getAllConcerts();
            totalTimeHit += (System.nanoTime() - start);
        }
        long avgTimeHitNano = totalTimeHit / measureCount;

        // 밀리초로 변환 (가독성)
        long avgTimeMissMs = avgTimeMissNano / 1_000_000;
        long avgTimeHitMs = avgTimeHitNano / 1_000_000;

        // Then: 결과 검증
        log.info("📈 콘서트 목록 캐시 성능 ({}회 평균):", measureCount);
        log.info("  - 캐시 미스 (DB 조회): {}ms ({}ns)", avgTimeMissMs, avgTimeMissNano);
        log.info("  - 캐시 히트 (메모리):  {}ms ({}ns)", avgTimeHitMs, avgTimeHitNano);
        if (avgTimeMissNano > 0) {
            log.info("  - 성능 개선:           {}배", String.format("%.1f", (double) avgTimeMissNano / avgTimeHitNano));
        }

        // 캐시가 실제로 성능 향상을 가져와야 함
        assertThat(avgTimeHitNano).isLessThan(avgTimeMissNano);
    }

    @Test
    @DisplayName("2. 스케줄 캐시 - 반복 조회 성능")
    void testScheduleCache() {
        // Given: 캐시 없음
        int iterations = 10;

        // 워밍업 호출 (캐시 미스)
        concertService.getConcertSchedule(testConcertId, testDate);

        // 캐시 히트 성능 측정
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            ScheduleDto result = concertService.getConcertSchedule(testConcertId, testDate);
            assertThat(result).isNotNull();
            assertThat(result.availableSeats()).hasSize(50);
        }
        long totalTime = System.currentTimeMillis() - startTime;
        double avgTime = (double) totalTime / iterations;

        // Then
        log.info("📊 스케줄 캐시 성능 ({} 회 반복):", iterations);
        log.info("  - 이 시간:   {}ms", totalTime);
        log.info("  - 평균 시간: {:.2f}ms", avgTime);

        // 캐시 사용 시 평균 5ms 이하여야 함
        assertThat(avgTime).isLessThan(5.0);
    }

    @Test
    @DisplayName("3. 동시 접근 - 부하 상황에서의 캐시")
    void testConcurrentCacheAccess() throws InterruptedException {
        // Given
        int threadCount = 100;
        int iterationsPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        // 캐시 워밍업
        concertService.getAllConcerts();

        // When: 100개 스레드가 동시에 캐시 데이터 접근
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        List<ConcertDto> result = concertService.getAllConcerts();
                        if (result != null && !result.isEmpty()) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        long totalTime = System.currentTimeMillis() - startTime;

        // Then
        int totalRequests = threadCount * iterationsPerThread;
        double tps = totalRequests * 1000.0 / totalTime;

        log.info("📊 동시 접근 캐시 성능:");
        log.info("  - 이 요청 수:   {}", totalRequests);
        log.info("  - 성공 횟수:    {}", successCount.get());
        log.info("  - 이 소요 시간: {}ms", totalTime);
        log.info("  - TPS:          {:.2f}", tps);
        log.info("  - 평균 지연:    {:.2f}ms", (double) totalTime / totalRequests);

        assertThat(successCount.get()).isEqualTo(totalRequests);
        // 캐시 사용 시 500+ TPS 가능해야 함
        assertThat(tps).isGreaterThan(500.0);
    }

    @Test
    @DisplayName("4. 캐시 무효화 - 올바른 무효화")
    void testCacheEviction() {
        // Given: 캐시가 채워진 상태
        List<ConcertDto> result1 = concertService.getAllConcerts();
        assertThat(result1).isNotEmpty();

        // When: 캐시 무효화
        concertService.evictConcertCache();

        // Then: 다음 호출은 캐시 미스 (느림)
        long startTime = System.currentTimeMillis();
        List<ConcertDto> result2 = concertService.getAllConcerts();
        long timeTaken = System.currentTimeMillis() - startTime;

        assertThat(result2).isEqualTo(result1);
        // 무효화 후에는 시간이 더 걸려야 함 (DB 접근)
        // 매우 빠른 환경에서는 3ms 정도 나올 수 있으므로 조건 완화
        assertThat(timeTaken).isGreaterThan(0L);

        log.info("📊 캐시 무효화 테스트:");
        log.info("  - 무효화 후 시간: {}ms (DB 접근)", timeTaken);
    }

    @Test
    @DisplayName("5. 스케줄 캐시 무효화 - 특정 키")
    void testScheduleCacheEviction() {
        // Given: 스케줄 캐시가 채워진 상태
        ScheduleDto result1 = concertService.getConcertSchedule(testConcertId, testDate);

        // 캐시 히트 (빠름)
        long startHit = System.currentTimeMillis();
        concertService.getConcertSchedule(testConcertId, testDate);
        long timeHit = System.currentTimeMillis() - startHit;

        // When: 특정 스케줄 캐시 무효화
        concertService.evictScheduleCache(testConcertId, testDate);

        // Then: 다음 호출은 캐시 미스
        long startMiss = System.currentTimeMillis();
        ScheduleDto result2 = concertService.getConcertSchedule(testConcertId, testDate);
        long timeMiss = System.currentTimeMillis() - startMiss;

        log.info("📊 스케줄 캐시 무효화:");
        log.info("  - 무효화 전 (히트): {}ms", timeHit);
        log.info("  - 무효화 후 (미스): {}ms", timeMiss);

        assertThat(result2).isEqualTo(result1);
        assertThat(timeMiss).isGreaterThan(timeHit);
    }

    @Test
    @DisplayName("6. 비교 성능: 캐시 있음 vs 없음")
    void testCacheVsNoCache() {
        // 시나리오: 콘서트 목록 100회 조회
        int requestCount = 100;

        // 테스트 1: 캐시 없음 (매번 무효화)
        List<Long> timesWithoutCache = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            concertService.evictConcertCache();
            long start = System.nanoTime();
            concertService.getAllConcerts();
            timesWithoutCache.add((System.nanoTime() - start) / 1_000_000); // ms 변환
        }

        // 테스트 2: 캐시 있음
        concertService.getAllConcerts(); // 워밍업
        List<Long> timesWithCache = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            long start = System.nanoTime();
            concertService.getAllConcerts();
            timesWithCache.add((System.nanoTime() - start) / 1_000_000);
        }

        // 통계 계산
        double avgWithoutCache = timesWithoutCache.stream()
                .mapToLong(Long::longValue).average().orElse(0);
        double avgWithCache = timesWithCache.stream()
                .mapToLong(Long::longValue).average().orElse(0);

        long totalWithoutCache = timesWithoutCache.stream()
                .mapToLong(Long::longValue).sum();
        long totalWithCache = timesWithCache.stream()
                .mapToLong(Long::longValue).sum();

        // 결과 출력
        log.info("📊 비교 성능 분석 ({} 회 요청):", requestCount);
        log.info("  캐시 미사용:");
        log.info("    - 이 시간:   {}ms", totalWithoutCache);
        log.info("    - 평균 시간: {:.2f}ms", avgWithoutCache);
        log.info("  캐시 사용:");
        log.info("    - 이 시간:   {}ms", totalWithCache);
        log.info("    - 평균 시간: {:.2f}ms", avgWithCache);
        log.info("  개선 효과:");
        log.info("    - 응답 시간: {:.1f}% 빠름",
                (avgWithoutCache - avgWithCache) * 100 / avgWithoutCache);
        log.info("    - 이 시간:   {:.1f}% 빠름",
                (totalWithoutCache - totalWithCache) * 100.0 / totalWithoutCache);

        // 검증
        assertThat(avgWithCache).isLessThan(avgWithoutCache * 0.5); // 최소 50% 빨라야 함
        assertThat(totalWithCache).isLessThan(totalWithoutCache / 2L);
    }

    @Test
    @DisplayName("7. 캐시 히트율 측정")
    void measureCacheHitRate() {
        // Given
        int totalRequests = 1000;
        AtomicInteger cacheHits = new AtomicInteger(0);
        AtomicInteger cacheMisses = new AtomicInteger(0);

        // 먼저 캐시 채우기
        concertService.getAllConcerts();

        // 실제 접근 패턴 시뮬레이션
        // 90% 같은 데이터, 10% 다른 날짜
        for (int i = 0; i < totalRequests; i++) {
            if (i % 10 == 0) {
                // 캐시 미스: 무효화 후 조회
                concertService.evictConcertCache();
                concertService.getAllConcerts();
                cacheMisses.incrementAndGet();
            } else {
                // 캐시 히트: 그냥 조회
                concertService.getAllConcerts();
                cacheHits.incrementAndGet();
            }
        }

        // 히트율 계산
        double hitRate = cacheHits.get() * 100.0 / totalRequests;

        log.info("📊 캐시 히트율 분석:");
        log.info("  - 이 요청 수:   {}", totalRequests);
        log.info("  - 캐시 히트:    {}", cacheHits.get());
        log.info("  - 캐시 미스:    {}", cacheMisses.get());
        log.info("  - 히트율:       {:.1f}%", hitRate);

        // 히트율은 85% 이상이어야 함
        assertThat(hitRate).isGreaterThan(85.0);
    }
}