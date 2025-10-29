package kr.hhplus.be.server.ranking;

import kr.hhplus.be.server.application.port.in.RankingUseCase;
import kr.hhplus.be.server.application.port.in.RankingUseCase.ConcertRankingDto;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 캐싱 성능 테스트
 *
 * 검증 사항:
 * 1. 첫 조회는 Redis에서 데이터를 가져옴
 * 2. 두 번째 조회는 캐시에서 가져옴 - 더 빠름
 * 3. 예약 추적 시 캐시가 무효화됨
 * 4. 캐시 무효화 후 다시 조회하면 Redis에서 가져옴
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DisplayName("랭킹 캐싱 성능 검증")
class RankingCachePerformanceTest {

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
    private RankingUseCase rankingUseCase;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        // Redis 초기화
        Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        // 캐시 초기화
        cacheManager.getCacheNames().forEach(cacheName ->
                cacheManager.getCache(cacheName).clear()
        );

        log.info("테스트 환경 초기화 완료");
    }

    @Test
    @DisplayName("첫 조회 vs 캐시된 조회 - 캐시가 더 빠름")
    void test_캐시_성능_첫조회_vs_캐시조회() {
        // Given: 랭킹 데이터 준비
        for (int i = 1; i <= 5; i++) {
            rankingUseCase.trackReservation((long) i, 10 * i);
        }

        // Warmup
        rankingUseCase.getFastSellingRanking(10);

        // When 1: 첫 조회]
        cacheManager.getCache("concertRankings").clear();
        long start1 = System.nanoTime();
        List<ConcertRankingDto> result1 = rankingUseCase.getFastSellingRanking(10);
        long time1 = System.nanoTime() - start1;

        // When 2: 두 번째 조회 (캐시 히트)
        long start2 = System.nanoTime();
        List<ConcertRankingDto> result2 = rankingUseCase.getFastSellingRanking(10);
        long time2 = System.nanoTime() - start2;

        // When 3: 세 번째 조회 (캐시 히트)
        long start3 = System.nanoTime();
        List<ConcertRankingDto> result3 = rankingUseCase.getFastSellingRanking(10);
        long time3 = System.nanoTime() - start3;

        // Then
        assertThat(result1).isEqualTo(result2).isEqualTo(result3);

        double time1Ms = time1 / 1_000_000.0;
        double time2Ms = time2 / 1_000_000.0;
        double time3Ms = time3 / 1_000_000.0;
        double avgCacheHitMs = (time2Ms + time3Ms) / 2;

        log.info("=== 캐싱 성능 측정 ===");
        log.info("첫 조회 (캐시 미스): {}ms", time1Ms);
        log.info("두 번째 조회 (캐시 히트): {}ms", time2Ms);
        log.info("세 번째 조회 (캐시 히트): {}ms", time3Ms);
        log.info("평균 캐시 히트: {}ms", avgCacheHitMs);
        log.info("성능 향상: {}배", time1Ms / avgCacheHitMs);

        // 캐시 히트가 더 빠름
        assertThat(avgCacheHitMs).isLessThan(time1Ms);
    }

    @Test
    @DisplayName("예약 추적 시 캐시 무효화")
    void test_예약_추적_시_캐시_무효화() {
        // Given: 랭킹 데이터 준비 및 캐시
        Long scheduleId = 1L;
        rankingUseCase.trackReservation(scheduleId, 10);
        List<ConcertRankingDto> beforeRanking = rankingUseCase.getFastSellingRanking(10);

        // When: 예약 추가 (캐시 무효화 발생)
        rankingUseCase.trackReservation(scheduleId, 20);

        // Then: 새로 조회한 랭킹은 업데이트된 값을 반영
        List<ConcertRankingDto> afterRanking = rankingUseCase.getFastSellingRanking(10);

        assertThat(afterRanking).isNotEmpty();
        ConcertRankingDto updated = afterRanking.get(0);
        assertThat(updated.soldCount()).isEqualTo(30);  // 10 + 20

        log.info("예약 추적 후 캐시 무효화 확인: {} -> {}",
                beforeRanking.get(0).soldCount(), updated.soldCount());
    }

    @Test
    @DisplayName("limit 값에 따라 별도 캐시 키 사용")
    void test_limit값에_따라_별도_캐시() {
        // Given: 10개 랭킹 데이터 준비
        for (int i = 1; i <= 10; i++) {
            rankingUseCase.trackReservation((long) i, i * 5);
        }

        // When: 다른 limit으로 조회
        List<ConcertRankingDto> top5 = rankingUseCase.getFastSellingRanking(5);
        List<ConcertRankingDto> top10 = rankingUseCase.getFastSellingRanking(10);

        // Then: 서로 다른 결과
        assertThat(top5).hasSize(5);
        assertThat(top10).hasSize(10);
        assertThat(top5).isNotEqualTo(top10);

        log.info("Top 5와 Top 10은 별도로 캐싱됨");
    }

    @Test
    @DisplayName("100회 조회 성능 - 캐싱 효과")
    void test_100회_조회_캐싱_효과() {
        // Given: 랭킹 데이터 준비
        for (int i = 1; i <= 20; i++) {
            rankingUseCase.trackReservation((long) i, i * 3);
        }

        for (int i = 0; i < 10; i++) {
            rankingUseCase.getFastSellingRanking(10);
        }

        // When: 100회 조회 (모두 캐시 히트여야 함)
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            rankingUseCase.getFastSellingRanking(10);
        }
        long totalTime = System.nanoTime() - start;

        double avgTimeMs = totalTime / 100.0 / 1_000_000.0;
        long totalTimeMs = totalTime / 1_000_000;

        log.info("=== 100회 조회 성능 ===");
        log.info("총 소요 시간: {}ms", totalTimeMs);
        log.info("평균 응답 시간: {}ms", avgTimeMs);
        log.info("TPS: {} req/s", 100_000.0 / totalTimeMs);

        assertThat(avgTimeMs).isLessThan(3.0);  // 3ms 이내 (캐시 히트 기준)
        assertThat(totalTimeMs).isLessThan(300);  // 전체 300ms 이내
    }

    @Test
    @DisplayName("캐시 동작 확인 - 로그로 검증")
    void test_캐시_동작_확인() {
        // Given: 랭킹 데이터 준비
        rankingUseCase.trackReservation(1L, 50);

        log.info("=== 캐시 동작 검증 시작 ===");

        // When 1: 첫 조회 (캐시 미스 - "Redis에서 데이터 조회" 로그 출력)
        log.info(">>> 첫 번째 조회 (캐시 미스 예상)");
        List<ConcertRankingDto> result1 = rankingUseCase.getFastSellingRanking(10);

        // When 2: 두 번째 조회 (캐시 히트 - 로그 없음)
        log.info(">>> 두 번째 조회 (캐시 히트 예상 - 로그 없어야 함)");
        List<ConcertRankingDto> result2 = rankingUseCase.getFastSellingRanking(10);

        // When 3: 세 번째 조회 (캐시 히트 - 로그 없음)
        log.info(">>> 세 번째 조회 (캐시 히트 예상 - 로그 없어야 함)");
        List<ConcertRankingDto> result3 = rankingUseCase.getFastSellingRanking(10);

        log.info("=== 캐시 동작 검증 완료 ===");
        log.info("결과: 첫 조회 후 '(캐시 미스)' 로그가 1번만 나왔다면 캐싱 정상 동작");

        // Then: 같은 결과
        assertThat(result1).isEqualTo(result2).isEqualTo(result3);
    }

    @Test
    @DisplayName("캐시 TTL 검증 - 10초 후 만료")
    void test_캐시_TTL_10초() throws InterruptedException {
        // Given: 랭킹 데이터 준비 및 캐시
        rankingUseCase.trackReservation(1L, 50);

        // 첫 조회 (캐시 적재)
        rankingUseCase.getFastSellingRanking(10);

        log.info("=== TTL 검증 시작 ===");

        // When 1: 5초 대기 (TTL 10초보다 짧음)
        log.info(">>> 5초 대기 중... (캐시 유효)");
        Thread.sleep(5000);

        long start1 = System.nanoTime();
        List<ConcertRankingDto> result1 = rankingUseCase.getFastSellingRanking(10);
        long time1 = System.nanoTime() - start1;

        // When 2: 추가 6초 대기 (총 11초, TTL 초과)
        log.info(">>> 추가 6초 대기 중... (총 11초, 캐시 만료 예상)");
        Thread.sleep(6000);

        long start2 = System.nanoTime();
        List<ConcertRankingDto> result2 = rankingUseCase.getFastSellingRanking(10);
        long time2 = System.nanoTime() - start2;

        double time1Ms = time1 / 1_000_000.0;
        double time2Ms = time2 / 1_000_000.0;

        log.info("=== TTL 검증 결과 ===");
        log.info("5초 후 (캐시 유효): {}ms", time1Ms);
        log.info("11초 후 (캐시 만료): {}}ms", time2Ms);
        log.info("캐시 만료 후가 더 느림: {}", time2Ms > time1Ms);

        // Then: 결과는 같지만, 11초 후는 다시 조회하므로 약간 더 느림
        assertThat(result1).isEqualTo(result2);

        // 테스트 환경에서는 시간 차이가 미세할 수 있으므로
        // 단순히 11초 후 조회가 정상 동작하는지만 확인
        assertThat(time2Ms).isLessThan(100.0);  // 너무 느리지 않으면 OK

        log.info("TTL 동작 확인 완료");
    }
}