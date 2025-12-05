package kr.hhplus.be.server.ranking;

import kr.hhplus.be.server.application.port.in.RankingUseCase;
import kr.hhplus.be.server.application.port.in.RankingUseCase.ConcertRankingDto;
import kr.hhplus.be.server.concurrency.config.TestTaskExecutorConfig;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * 콘서트 랭킹 서비스 핵심 테스트
 *
 * 과제 요구사항: 빠른 매진 랭킹 (통합)
 */
@Slf4j
@SpringBootTest
@Import(TestTaskExecutorConfig.class)
@DisplayName("콘서트 랭킹 서비스 테스트")
class ConcertRankingServiceTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RankingUseCase rankingService;

    @Autowired
    @Qualifier("concurrencyTestExecutor")
    private TaskExecutor taskExecutor;

    @BeforeEach
    void setUp() {
        cleanupRedis();
    }

    private void cleanupRedis() {
        Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("100명 동시 예약 → 정확히 100개 집계 (동시성 검증)")
    void concurrentReservationsShouldBeCountedAccurately() throws InterruptedException {
        // Given
        Long scheduleId = System.currentTimeMillis();
        int threadCount = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        // When - 100명 동시 예약
        for (int i = 0; i < threadCount; i++) {
            taskExecutor.execute(() -> {
                try {
                    startLatch.await();
                    rankingService.trackReservation(scheduleId, 1);
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(endLatch.await(10, TimeUnit.SECONDS)).isTrue();

        // Then - 정확히 100개 집계
        List<ConcertRankingDto> rankings = rankingService.getFastSellingRanking(10);

        assertThat(errorCount.get()).isEqualTo(0);
        assertThat(rankings).isNotEmpty();
        assertThat(rankings.get(0).soldCount()).isEqualTo(100);
        assertThat(rankings.get(0).isSoldOut()).isTrue();

        log.info("동시성 테스트 통과: {}석 정확히 집계, 매진 상태: {}",
                rankings.get(0).soldCount(), rankings.get(0).isSoldOut());
    }

    @Test
    @DisplayName("99석 vs 100석 - 매진 상태 정확히 반영")
    void shouldReflectSoldOutStatusAccurately() throws InterruptedException {
        // Given
        Long scheduleId = System.currentTimeMillis();


        // When - 99석 판매
        for (int i = 0; i < 99; i++) {
            rankingService.trackReservation(scheduleId, 1);
        }

        Thread.sleep(100);

        // Then - 랭킹에 존재, 매진 아님
        List<ConcertRankingDto> rankings = rankingService.getFastSellingRanking(10);
        ConcertRankingDto concert = rankings.stream()
                .filter(dto -> dto.scheduleId().equals(scheduleId))
                .findFirst()
                .orElseThrow();

        assertThat(concert.soldCount()).isEqualTo(99);
        assertThat(concert.isSoldOut()).isFalse();
        assertThat(concert.soldOutSeconds()).isNull();
        log.info("99석: soldCount={}, isSoldOut={}", concert.soldCount(), concert.isSoldOut());

        // When - 1석 추가 판매 (매진)
        rankingService.trackReservation(scheduleId, 1);

        // Then - 매진 상태로 변경
        rankings = rankingService.getFastSellingRanking(10);
        concert = rankings.stream()
                .filter(dto -> dto.scheduleId().equals(scheduleId))
                .findFirst()
                .orElseThrow();

        assertThat(concert.soldCount()).isEqualTo(100);
        assertThat(concert.isSoldOut()).isTrue();
        assertThat(concert.soldOutSeconds()).isNotNull();
        log.info("100석: soldCount={}, isSoldOut={}, soldOutSeconds={}초",
                concert.soldCount(), concert.isSoldOut(), concert.soldOutSeconds());
    }

    @Test
    @DisplayName("매진 후 추가 예약 시도 → 집계 차단")
    void soldOutConcertShouldBlockAdditionalReservations() {
        // Given - 100석 매진
        Long scheduleId = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            rankingService.trackReservation(scheduleId, 1);
        }

        // When - 매진 후 50회 추가 시도
        for (int i = 0; i < 50; i++) {
            rankingService.trackReservation(scheduleId, 1);
        }

        // Then - 100석에서 멈춤
        List<ConcertRankingDto> rankings = rankingService.getFastSellingRanking(10);
        ConcertRankingDto concert = rankings.stream()
                .filter(dto -> dto.scheduleId().equals(scheduleId))
                .findFirst()
                .orElseThrow();

        assertThat(concert.soldCount()).isEqualTo(100);
        log.info("매진 캐싱: 100석에서 멈춤, 추가 집계 차단");
    }

    @Test
    @DisplayName("여러 공연 동시 판매 → 판매 속도 순 랭킹")
    void multipleConcertsShouldBeRankedByVelocity() throws InterruptedException {
        // Given - 3개 공연
        Long schedule1 = System.currentTimeMillis();
        Long schedule2 = System.currentTimeMillis() + 1;
        Long schedule3 = System.currentTimeMillis() + 2;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(90);

        // When - 동시 판매
        scheduleSales(schedule1, 50, startLatch, endLatch);
        scheduleSales(schedule2, 30, startLatch, endLatch);
        scheduleSales(schedule3, 10, startLatch, endLatch);

        startLatch.countDown();
        assertThat(endLatch.await(10, TimeUnit.SECONDS)).isTrue();

        // Then - 판매량 순 랭킹
        List<ConcertRankingDto> rankings = rankingService.getFastSellingRanking(10);

        assertThat(rankings).hasSizeGreaterThanOrEqualTo(3);
        assertThat(rankings.get(0).scheduleId()).isEqualTo(schedule1);
        assertThat(rankings.get(0).soldCount()).isEqualTo(50);
        assertThat(rankings.get(1).scheduleId()).isEqualTo(schedule2);
        assertThat(rankings.get(1).soldCount()).isEqualTo(30);
        assertThat(rankings.get(2).scheduleId()).isEqualTo(schedule3);
        assertThat(rankings.get(2).soldCount()).isEqualTo(10);

        log.info("랭킹 정확도: 50석 > 30석 > 10석 순서 정확");
    }

    private void scheduleSales(Long scheduleId, int count,
                               CountDownLatch startLatch,
                               CountDownLatch endLatch) {
        for (int i = 0; i < count; i++) {
            taskExecutor.execute(() -> {
                try {
                    startLatch.await();
                    rankingService.trackReservation(scheduleId, 1);
                } catch (Exception e) {
                    log.error("예약 실패", e);
                } finally {
                    endLatch.countDown();
                }
            });
        }
    }

    @Test
    @DisplayName("매진 캐시 성능 - 100회 호출 1초 이내")
    void soldOutCachePerformance() {
        // Given - 매진 상태
        Long scheduleId = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            rankingService.trackReservation(scheduleId, 1);
        }

        // When - 100회 호출
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            rankingService.trackReservation(scheduleId, 1);
        }
        long duration = System.currentTimeMillis() - startTime;

        // Then - 1초 이내
        assertThat(duration).isLessThan(1000L);

        log.info("캐시 성능: 100회 호출 {}ms (1초 이내)", duration);
    }
}