# 콘서트 예약 시스템 - Redis 기반 랭킹/대기열 구현 회고

> 항해 플러스 LITE 7주차 과제  
> 2025.11.03

---

## 1. Ranking Design - 빠른 매진 랭킹 시스템

### 1.1 핵심 구현

#### 판매 속도 기반 랭킹

```java
// ConcertRankingService.java
private void updateVelocityRanking(Long scheduleId, long soldCount) {
    Map<String, String> stats = rankingPort.getStats(String.valueOf(scheduleId));
    String startTimeStr = stats.get("startTime");

    if (startTimeStr == null) {
        log.warn("startTime이 없음 - scheduleId: {}", scheduleId);
        return;
    }

    try {
        long startTime = Long.parseLong(startTimeStr);
        long elapsedMinutes = (System.currentTimeMillis() - startTime) / 60000;
        if (elapsedMinutes < 1) elapsedMinutes = 1;

        // 분당 판매량 = 판매 속도
        double velocity = soldCount / (double) elapsedMinutes;

        // Port를 통해 랭킹 업데이트
        rankingPort.updateVelocityRanking(String.valueOf(scheduleId), velocity);

        log.debug("판매 속도 업데이트 - scheduleId: {}, velocity: {:.2f} tickets/min",
                scheduleId, velocity);
    } catch (NumberFormatException e) {
        log.error("startTime 파싱 실패 - scheduleId: {}, startTime: {}",
                scheduleId, startTimeStr);
    }
}
```

**설계 의도**
- 단순 판매 수량만으로는 오래된 공연이 유리함
- 시간을 고려한 "속도" 개념으로 "빠르게 팔리는" 공연 파악
- 실시간성 있는 랭킹 제공

#### Redis 자료구조 활용

```java
// RedisRankingAdapter.java
@Override
public long incrementSoldCount(String scheduleId, int increment) {
    String key = SCHEDULE_STATS + scheduleId;

    // Redis HINCRBY는 atomic 연산
    Long newCount = redisTemplate.opsForHash().increment(key, "soldCount", increment);

    // lastSaleTime도 함께 업데이트
    redisTemplate.opsForHash().put(key, "lastSaleTime",
            String.valueOf(System.currentTimeMillis()));

    return newCount != null ? newCount : increment;
}

@Override
public void updateVelocityRanking(String scheduleId, double score) {
    redisTemplate.opsForZSet().add(VELOCITY_RANKING, "schedule:" + scheduleId, score);
}
```

**선택한 자료구조**
- **Hash**: 스케줄별 통계 정보 저장 (soldCount, startTime, totalSeats)
- **Sorted Set**: velocity를 score로 사용하여 자동 정렬
- `reverseRange(0, limit-1)`로 상위 랭킹 조회

#### 캐싱 전략

```java
// ConcertRankingService.java
@Cacheable(value = "concertRankings", key = "#limit")
public List<ConcertRankingDto> getFastSellingRanking(int limit) {
    log.debug("랭킹 조회 - Port를 통한 데이터 조회 (캐시 미스)");

    // Port를 통해 상위 랭킹 조회
    Set<String> topSchedules = rankingPort.getTopByVelocity(limit);

    if (topSchedules.isEmpty()) {
        return List.of();
    }

    // 1. scheduleId 목록 추출
    List<Long> scheduleIds = topSchedules.stream()
            .map(this::extractScheduleId)
            .filter(Objects::nonNull)
            .toList();

    // 2. 배치로 한 번에 조회
    Map<Long, ConcertSchedule> scheduleMap = schedulePort.findAllByIds(scheduleIds);

    // 3. 랭킹 데이터 조합
    List<ConcertRankingDto> result = new ArrayList<>();
    int rank = 1;

    for (String scheduleKey : topSchedules) {
        try {
            Long scheduleId = extractScheduleId(scheduleKey);
            if (scheduleId == null) continue;

            Map<String, String> stats = rankingPort.getStats(String.valueOf(scheduleId));
            if (stats.isEmpty()) continue;

            // 통계 정보 추출
            int soldCount = getIntValue(stats.get("soldCount"));
            double velocity = calculateVelocity(stats);
            boolean isSoldOut = stats.containsKey("soldOutTime");
            Integer soldOutSeconds = isSoldOut ?
                    getIntValue(stats.get("soldOutSeconds")) : null;

            // Map에서 조회
            String concertName = Optional.ofNullable(scheduleMap.get(scheduleId))
                    .map(schedule -> "Concert #" + scheduleId)
                    .orElse("Unknown Concert #" + scheduleId);

            result.add(new ConcertRankingDto(
                    rank++,
                    scheduleId,
                    concertName,
                    soldCount,
                    velocity,
                    isSoldOut,
                    soldOutSeconds
            ));

        } catch (NumberFormatException e) {
            log.warn("숫자 형식 오류 - scheduleKey: {}", scheduleKey);
        } catch (Exception e) {
            log.error("랭킹 항목 처리 실패 - scheduleKey: {}", scheduleKey, e);
        }
    }

    return result;
}

@CacheEvict(value = "concertRankings", allEntries = true)
public void trackReservation(Long scheduleId, int seatCount) {
    String scheduleIdStr = String.valueOf(scheduleId);

    // 매진 체크
    Map<String, String> stats = rankingPort.getStats(scheduleIdStr);
    if (stats.containsKey("soldOutTime")) {
        log.debug("이미 매진된 공연 - scheduleId: {}", scheduleId);
        return;
    }

    // 실제 총 좌석 수 조회
    int totalSeats = getTotalSeats(scheduleId, stats);

    // 첫 판매 시간 기록
    rankingPort.setStartTimeIfAbsent(scheduleIdStr, System.currentTimeMillis());

    // 판매 수량 증가
    long newSoldCount = rankingPort.incrementSoldCount(scheduleIdStr, seatCount);

    log.debug("예약 추적 - scheduleId: {}, 추가: {}석, 누적: {}석 / 총: {}석",
            scheduleId, seatCount, newSoldCount, totalSeats);

    // 판매 속도 랭킹 업데이트
    updateVelocityRanking(scheduleId, newSoldCount);

    // 실제 좌석 수 기준 매진 체크
    if (newSoldCount >= totalSeats) {
        recordSoldOut(scheduleId, totalSeats);
    }
}
```

### 1.2 겪었던 문제들

#### 문제 1. Record 타입 직렬화 실패

**상황**
```
ClassCastException: LinkedHashMap cannot be cast to ConcertRankingDto
```

처음 `GenericJackson2JsonRedisSerializer`를 사용했는데, Record 타입을 제대로 역직렬화하지 못했다. Redis에서 가져온 데이터가 LinkedHashMap으로 변환되어 타입 정보가 유실되었다.

**해결**
```java
// RedisConfig.java
@Bean
public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    JdkSerializationRedisSerializer serializer = new JdkSerializationRedisSerializer();

    // 기본 캐시 설정
    RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .disableCachingNullValues()
            .serializeKeysWith(
                    RedisSerializationContext.SerializationPair.fromSerializer(
                            new StringRedisSerializer()
                    )
            )
            .serializeValuesWith(
                    RedisSerializationContext.SerializationPair.fromSerializer(serializer)
            );

    RedisCacheConfiguration concertsConfig = defaultConfig.entryTtl(Duration.ofDays(1));
    RedisCacheConfiguration concertDetailConfig = defaultConfig.entryTtl(Duration.ofDays(1));
    RedisCacheConfiguration scheduleConfig = defaultConfig.entryTtl(Duration.ofMinutes(1));
    RedisCacheConfiguration rankingConfig = defaultConfig.entryTtl(Duration.ofSeconds(10));

    return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig.entryTtl(Duration.ofMinutes(10)))
            .withCacheConfiguration("concerts", concertsConfig)
            .withCacheConfiguration("concertDetail", concertDetailConfig)
            .withCacheConfiguration("schedule", scheduleConfig)
            .withCacheConfiguration("concertRankings", rankingConfig)
            .build();
}

// DTO에 Serializable 추가
record ConcertRankingDto(
        int rank,
        Long scheduleId,
        String concertName,
        int soldCount,
        double velocityPerMinute,
        boolean isSoldOut,
        Integer soldOutSeconds
) implements Serializable {}
```

`JdkSerializationRedisSerializer`로 변경하고 DTO에 `Serializable`을 구현해서 타입 안전성을 보장했다.

---

#### 문제 2. N+1 문제 발견과 배치 조회

**상황:**

처음엔 랭킹 조회 시 for문 안에서 각 스케줄마다 DB를 조회했다.

```java
// Before: for문 안에서 매번 DB 조회
for (String scheduleKey : topSchedules) {
    Long scheduleId = extractScheduleId(scheduleKey);
    
    // 매번 DB 쿼리 발생! (N+1)
    Optional<ConcertSchedule> schedule = schedulePort.findById(scheduleId);
    String concertName = schedule.map(s -> "Concert #" + scheduleId).orElse("Unknown");
    // ...
}
```

10개 랭킹이면 DB 쿼리 10번, 100개면 100번...

**해결: 3단계 조회 전략**

```java
@Override
@Cacheable(value = "concertRankings", key = "#limit")
public List<ConcertRankingDto> getFastSellingRanking(int limit) {
    log.debug("랭킹 조회 - Port를 통한 데이터 조회 (캐시 미스)");
    
    Set<String> topSchedules = rankingPort.getTopByVelocity(limit);
    
    if (topSchedules.isEmpty()) {
        return List.of();
    }
    
    // 1. scheduleId 목록 추출
    List<Long> scheduleIds = topSchedules.stream()
            .map(this::extractScheduleId)
            .filter(Objects::nonNull)
            .toList();
    
    // 2. 배치로 한 번에 조회 - 핵심!
    Map<Long, ConcertSchedule> scheduleMap = schedulePort.findAllByIds(scheduleIds);
    
    // 3. 랭킹 데이터 조합
    List<ConcertRankingDto> result = new ArrayList<>();
    int rank = 1;
    
    for (String scheduleKey : topSchedules) {
        try {
            Long scheduleId = extractScheduleId(scheduleKey);
            if (scheduleId == null) continue;
            
            Map<String, String> stats = rankingPort.getStats(String.valueOf(scheduleId));
            if (stats.isEmpty()) continue;
            
            // 통계 정보 추출
            int soldCount = getIntValue(stats.get("soldCount"));
            double velocity = calculateVelocity(stats);
            boolean isSoldOut = stats.containsKey("soldOutTime");
            Integer soldOutSeconds = isSoldOut ?
                    getIntValue(stats.get("soldOutSeconds")) : null;
            
            // Map에서 O(1) 조회 - DB 쿼리 없음!
            String concertName = Optional.ofNullable(scheduleMap.get(scheduleId))
                    .map(schedule -> "Concert #" + scheduleId)
                    .orElse("Unknown Concert #" + scheduleId);
            
            result.add(new ConcertRankingDto(
                    rank++,
                    scheduleId,
                    concertName,
                    soldCount,
                    velocity,
                    isSoldOut,
                    soldOutSeconds
            ));
            
        } catch (NumberFormatException e) {
            log.warn("숫자 형식 오류 - scheduleKey: {}", scheduleKey);
        } catch (Exception e) {
            log.error("랭킹 항목 처리 실패 - scheduleKey: {}", scheduleKey, e);
        }
    }
    
    return result;
}
```

**개선 효과**
```
Before: SELECT * FROM schedule WHERE id = 1;
        SELECT * FROM schedule WHERE id = 2;
        ...
        SELECT * FROM schedule WHERE id = 10;
        → 10번 쿼리

After:  SELECT * FROM schedule WHERE id IN (1,2,...,10);
        → 1번 쿼리
```

**배운 점**
- 일단 동작하게 만들고, 성능 테스트 중에 문제 발견
- `findAllByIds()`로 배치 조회 → Map으로 변환
- 예외 처리로 일부 실패해도 전체는 성공

---

#### 문제 3. 성능 측정의 어려움

**초기 코드**
```java
long start = System.currentTimeMillis();
service.getFastSellingRanking(10);
long time = System.currentTimeMillis() - start;
```

**문제점**
- 1ms vs 1ms 비교 → 측정 오차 범위
- 캐시 히트가 오히려 더 느리게 측정되기도 함
- JVM Hotspot 최적화 전에 측정

**해결**
```java
// RankingCachePerformanceTest.java
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
```

**개선 사항**
1. **JVM Warmup**: 10회 사전 실행으로 Hotspot 최적화
2. **100회 반복**: 통계적으로 신뢰할 수 있는 데이터
3. **나노초 단위**: `System.nanoTime()` 사용

**결과**
```
100회 평균 응답 시간: 0.2ms
TPS: 2,000+ req/s
```

---

## 2. Asynchronous Design - Redis 대기열 시스템

### 2.1 핵심 구현

#### 원자적 카운터로 동시성 제어

```java
// RedisQueueAdapter.java
@Override
public QueueToken issue(String userId) {
    // 기존 토큰 확인
    String existingToken = redisTemplate.opsForValue().get(USER_TOKEN_MAP + userId);
    if (existingToken != null && isValidToken(existingToken)) {
        return new QueueToken(existingToken);
    }

    // 새 토큰 생성
    String token = UUID.randomUUID().toString();
    long timestamp = Instant.now().toEpochMilli();

    // 토큰 정보 저장
    saveTokenInfo(token, userId, timestamp);
    redisTemplate.opsForValue().set(USER_TOKEN_MAP + userId, token, TOKEN_TTL_MINUTES, TimeUnit.MINUTES);

    // 원자적 카운터로 동시성 제어
    Long currentCount = redisTemplate.opsForValue().increment("queue:active:counter");

    if (currentCount != null && currentCount <= MAX_ACTIVE_USERS) {
        // 100명 이내면 활성화
        activateToken(token);
        log.info("토큰 발급 완료: userId={}, activated=true", userId);
    } else {
        // 초과하면 카운터 롤백하고 대기열 추가
        redisTemplate.opsForValue().decrement("queue:active:counter");
        redisTemplate.opsForZSet().add(WAITING_QUEUE, token, (double) timestamp);
        redisTemplate.opsForHash().put(TOKEN_INFO + token, "status", "WAITING");
        log.info("토큰 발급 완료: userId={}, activated=false", userId);
    }

    return new QueueToken(token);
}
```

**핵심 아이디어**
- Redis의 `increment/decrement`는 원자적 연산
- 분산락 없이도 동시성 제어 가능
- 초과 시 즉시 롤백하여 정확한 카운트 유지

#### Redis 자료구조 활용

```java
private static final String WAITING_QUEUE = "queue:waiting";
private static final String ACTIVE_SET = "queue:active";
private static final String TOKEN_INFO = "queue:token:";
private static final String USER_TOKEN_MAP = "queue:user:";
private static final String LOCK_ACTIVATE = "lock:queue:activate";

private static final int MAX_ACTIVE_USERS = 100;
private static final int TOKEN_TTL_MINUTES = 10;

// 대기 순번 조회
@Override
public Long getWaitingPosition(String token) {
    Long rank = redisTemplate.opsForZSet().rank(WAITING_QUEUE, token);
    return rank != null ? rank + 1 : null;
}

// 대기열에서 활성화
@Override
public void activateNextUsers(int count) {
    if (count <= 0) return;

    try {
        distributedLock.executeWithLock(
                LOCK_ACTIVATE, 10, 3, 200,
                () -> {
                    cleanupExpiredTokens();

                    Long activeCount = getActiveCount();
                    int availableSlots = MAX_ACTIVE_USERS - activeCount.intValue();
                    if (availableSlots <= 0) return null;

                    int toActivate = Math.min(count, availableSlots);
                    Set<String> tokens = redisTemplate.opsForZSet().range(WAITING_QUEUE, 0, toActivate - 1);

                    if (tokens != null && !tokens.isEmpty()) {
                        tokens.forEach(this::activateToken);
                        log.info("대기열 활성화: {}명", tokens.size());
                    }
                    return null;
                }
        );
    } catch (Exception e) {
        log.error("대기열 활성화 실패", e);
    }
}
```

**선택한 자료구조**
- **Sorted Set** (대기열): 타임스탬프를 score로 사용, FIFO 보장
- **Set** (활성 사용자): O(1) 존재 여부 확인
- **Hash** (토큰 정보): userId, status, issuedAt 저장
- **String** (원자적 카운터): increment/decremnt로 동시성 제어

#### 분산락 활용

```java
// RedisDistributedLock.java
public <T> T executeWithLock(
        String lockKey,
        long ttlSeconds,
        int retryCount,
        long retryDelayMillis,
        Supplier<T> action
) {
    String lockValue = UUID.randomUUID().toString();
    int attempts = 0;

    while (attempts < retryCount) {
        boolean acquired = tryLock(lockKey, lockValue, ttlSeconds);

        if (acquired) {
            try {
                log.info("락 획득 성공: key={}, value={}", lockKey, lockValue);
                return action.get();
            } finally {
                // unlock 호출 제거 - TTL로 자동 만료
                // unlock의 race condition을 피하기 위해 명시적 해제를 하지 않음
                log.info("락은 TTL({}초)로 자동 만료됨: key={}", ttlSeconds, lockKey);
            }
        }

        attempts++;
        if (attempts < retryCount) {
            log.info("락 획득 실패, 재시도 {}/{}: key={}", attempts, retryCount, lockKey);
            sleep(retryDelayMillis);
        }
    }

    throw LockAcquisitionException.of(lockKey, retryCount);
}
```

**설계 포인트**
- `setIfAbsent(key, value, Duration)` 사용
- unlock 메서드 제거 → TTL 자동 만료 방식
- Race Condition 방지

### 2.2 겪었던 문제들

#### 문제 1. unlock의 Race Condition

**초기 구현:**
```java
public boolean unlock(String key, String value) {
    String currentValue = redisTemplate.opsForValue().get(key);
    
    // 내 락인지 확인 후 삭제
    if (value.equals(currentValue)) {
        redisTemplate.delete(key);
        return true;
    }
    return false;
}
```

**문제 시나리오:**
```
Thread A: get(lock) → "my-value"
Thread A: 비교 성공
[이 사이에 TTL 만료!]
Thread B: 새로운 락 획득
Thread A: delete → Thread B의 락까지 삭제!
```

**해결 선택지 비교:**

| 방식 | 장점 | 단점 |
|------|------|------|
| Lua 스크립트 | 완벽한 원자성 보장 | 복잡도 증가, 디버깅 어려움 |
| TTL 자동 만료 | 코드 단순, 이해 쉬움 | TTL > 처리시간 보장 필요 |

**선택:**
- 우리 처리 시간: ~1초
- TTL 설정: 10초
- 10배 여유 → TTL 자동 만료 선택
- 추후 처리 시간이 길어지면 Lua 도입 고려


#### 문제 2. 테스트 격리 실패

**상황**
```
Test 1 실행: 100명 활성화 ✓
Test 2 실행: 대기 순번 1번 예상 → 실제 135번!
```

개별 테스트는 성공하는데 전체 실행하면 실패했다.

**원인**
- Testcontainers로 Redis를 띄워도 데이터는 테스트 간 유지됨
- `@DirtiesContext`는 ApplicationContext만 재시작
- Redis 데이터는 그대로 남아있음

**해결**
```java
// CachePerformanceTest.java
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
```

명시적으로 `flushDb()`를 호출하고 100ms 대기하여 해결했다.


#### 문제 3. 150명 동시 테스트 성공!

**테스트 시나리오:**
```java
@Test
void concurrentIssue() throws InterruptedException {
    int totalUsers = 150;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch endLatch = new CountDownLatch(totalUsers);
    ExecutorService executor = Executors.newFixedThreadPool(50);
    
    // 150명이 동시에 토큰 발급 요청
    for (int i = 1; i <= totalUsers; i++) {
        executor.submit(() -> {
            startLatch.await();  // 모두 대기
            QueueToken token = queuePort.issue(userId);
            // ...
            endLatch.countDown();
        });
    }
    
    startLatch.countDown();  // 동시 시작!
    endLatch.await(15, TimeUnit.SECONDS);
}
```

**결과:**
```
총 요청: 150명
활성화 성공: 100명 ✓
대기열 추가: 50명 ✓
소요 시간: 2~3초
```

**검증된 것:**
- 원자적 카운터만으로 정확히 100명 제어 가능
- 분산락 없이도 동시성 제어 성공
- CountDownLatch로 "진짜 동시" 요청 시뮬레이션

처음엔 "진짜 동시에 요청하는 테스트"를 어떻게 만들지 막막했는데, CountDownLatch가 정답이었다!

---

## 3. 회고

### 3.1 측정의 중요성

처음엔 캐시를 적용하면 당연히 빨라질 거라고 생각했다. 그런데 막상 테스트를 돌려보니 둘 다 1ms였다. 왜 빨라지지 않을지 한참을 고민했다. 문제는 내가 제대로 측정하지 못하고 있는 것이었다.

JVM Warmup이 필요하다는 것도, `System.currentTimeMillis()`가 아니라 `System.nanoTime()`을 써야 한다는 것도, 한 번이 아니라 100번 반복해서 통계를 내야 한다는 것도 이번에 처음 알았다.

Warmup 후 제대로 측정하니까 캐시 효과가 명확하게 보였다. 측정 결과 10배 정도는 빨라졌다.

### 3.2 분산락의 unlock 구현

이번 과제에서 가장 고민했던 부분은 분산락의 unlock 구현이었다.

**선택지 1: Lua 스크립트**
- 완벽한 원자성 보장
- 팀원들이 Lua에 대한 이해도가 높지 않다면 이해 및 유지보수 어려움

**선택지 2: TTL 자동 만료**
- 코드가 단순하고 이해하기 쉬움
- 하지만 처리 시간이 TTL을 초과하면 문제 가능성

이미 Lua 스크립트는 지양하는 게 좋다는 피드백을 받았었기도 했고, 우리 시나리오에서는 처리 시간이 1초도 안 걸릴 것이라고 생각했기 때문에 TTL 방식을 선택했다.

### 3.3 동시성 문제

이론으로 배울 때는 Race Condition을 막으려면 단순히 락을 걸면 될거라고 생각했다. 근데 막상 구현해보니 그게 아니었다.

- 어디서부터 어디까지 락을 걸어야 하나?
- 트랜잭션과 락의 순서는?
- TaskExecutor의 큐 크기도 고려해야 하나?
- unlock의 get-check-delete도 Race Condition이 생기네?

하나하나가 다 고민거리였다. 특히 TaskExecutor Deadlock은 정말 예상 못 한 문제였다. 큐가 꽉 차면 호출 스레드에서 직접 실행한다는 걸 알고 있었지만, 그게 CountDownLatch와 만나면 Deadlock이 된다는 건 생각 못 했다.

동시성 문제는 코드만 봐서는 절대 발견 못하므로 테스트로만 발견 가능하다. 그래서 150명 동시 테스트, 1000명 동시 테스트가 필요헀다.

### 3.4 아쉬운 점

1. 실제 부하 테스트는 못 해봤다. 로컬에서 1000명 동시 테스트까지는 했는데, 실제 프로덕션 환경에서 수만 명이 동시에 들어오면 어떻게 될지는 모르겠다. 추후에 AWS에 배포해서 JMeter로 실제 부하 테스트를 해 보는 게 좋을 것 같다.

2. TTL 자동 만료의 한계가 있을 수 있다. 지금 방식은 처리 시간이 TTL을 넘으면 문제가 생길 수 있다. 네트워크 지연과 같은 문제로 TTL을 넘을 수도 있는데, 그런 엣지 케이스까지는 고려하지 못했다.

### 3.5 느낀점

이번 과제를 하기 전엔 Redis를 제대로 사용해 본 적도 없었는데, 이제는 활용할 수 있게 되었다. 동시성 테스트 검증에도 조금은 익숙해졌고, 캐싱을 적용해 성능 개선을 측정해보기도 했다.

취업 준비를 하면서 내가 할 수 있는 게 많이 없고, 남들보다 뒤쳐진다는 생각에 불안한 마음이 많이 들었었는데, 이제는 기술 면접에서 나도 할 이야기가 생겼다.

아직도 난 많이 부족하고, 완벽하지 않으며, 배워야 하고 고쳐야 하는 것들이 많다. 실수도 많이 하고 헤매는 시간도 길지만 그만큼 성장할 가능성이 크다고 믿고 싶다!

이제 얼마 남지 않은 다음 스텝들이 아쉽기도 하고, 기대도 된다!