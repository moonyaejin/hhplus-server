package kr.hhplus.be.server.ranking;

import kr.hhplus.be.server.application.port.in.PaymentUseCase;
import kr.hhplus.be.server.application.port.in.QueueUseCase;
import kr.hhplus.be.server.application.port.in.RankingUseCase;
import kr.hhplus.be.server.application.port.in.ReservationUseCase;
import kr.hhplus.be.server.application.port.in.SeatQueryUseCase;
import kr.hhplus.be.server.concurrency.config.TestTaskExecutorConfig;
import kr.hhplus.be.server.domain.queue.QueueTokenNotActiveException;
import kr.hhplus.be.server.domain.reservation.QueueTokenExpiredException;
import kr.hhplus.be.server.domain.reservation.SeatAlreadyAssignedException;
import kr.hhplus.be.server.domain.reservation.SeatAlreadyConfirmedException;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertScheduleJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertScheduleJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWalletJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.queue.jpa.entity.QueueTokenJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.queue.jpa.repository.QueueTokenJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.user.jpa.entity.UserJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.user.jpa.repository.UserJpaRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * 전체 플로우 티켓팅 시나리오 테스트
 *
 * 포함 요소:
 * 1. 대기열 토큰 발급 및 검증
 * 2. 좌석 조회 (DB 읽기)
 * 3. 좌석 임시 할당 (분산 락 + DB 쓰기)
 * 4. 예약 확정 (트랜잭션 + 결제)
 * 5. 랭킹 업데이트 (Redis)
 *
 * 실제 병목 지점:
 * - DB 락 경합
 * - 분산 락 대기 시간
 * - 트랜잭션 커밋 지연
 * - 네트워크 레이턴시
 */
@Slf4j
@SpringBootTest
@Import(TestTaskExecutorConfig.class)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("티켓팅 전체 플로우")
class TicketingScenarioTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
    }

    @Autowired private QueueUseCase queueUseCase;
    @Autowired private ReservationUseCase reservationUseCase;
    @Autowired private SeatQueryUseCase seatQueryUseCase;
    @Autowired private PaymentUseCase paymentUseCase;
    @Autowired private RankingUseCase rankingUseCase;

    @Autowired private UserJpaRepository userRepository;
    @Autowired private ConcertJpaRepository concertRepository;
    @Autowired private ConcertScheduleJpaRepository scheduleRepository;
    @Autowired private UserWalletJpaRepository walletRepository;
    @Autowired private QueueTokenJpaRepository queueTokenRepository;
    @Autowired private RedisTemplate<String, String> redisTemplate;

    @Autowired
    @Qualifier("extremeTestExecutor")
    private TaskExecutor taskExecutor;

    private Long concertScheduleId;
    private static final int TOTAL_SEATS = 50;  // 실제 좌석 수
    private static final long TICKET_PRICE = 10_000L;

    @BeforeEach
    void setUp() {
        log.info("테스트 환경 초기화 시작");

        // Redis 초기화
        cleanupRedis();

        // 콘서트 및 스케줄 생성
        ConcertJpaEntity concert = new ConcertJpaEntity("인기 아이돌 콘서트");
        concert = concertRepository.save(concert);

        ConcertScheduleJpaEntity schedule = new ConcertScheduleJpaEntity(
                concert,
                LocalDate.now().plusDays(7),
                TOTAL_SEATS
        );
        schedule = scheduleRepository.save(schedule);
        concertScheduleId = schedule.getId();

        log.info("테스트 데이터 준비 완료 - scheduleId: {}, 좌석: {}개", concertScheduleId, TOTAL_SEATS);
    }

    private void cleanupRedis() {
        try {
            Set<String> keys = redisTemplate.keys("*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Redis 초기화 실패: {}", e.getMessage());
        }
    }

    /**
     * 시나리오 : 200명 동시 접속
     *
     * - 200명이 대기열 토큰 받음
     * - 50명만 활성화 (대기열 통과)
     * - 50개 좌석을 놓고 경쟁
     *
     * 예상 결과:
     * - 성공: 최대 50명 (좌석 수)
     * - 대기열 차단: 150명
     * - 좌석 경합 실패: ~10명
     * - 평균 응답시간: 200-500ms
     * - P99: 1-3초
     */
    @Disabled("비동기 결제로 변경 - 별도 Kafka 통합 테스트로 대체 예정")
    @Test
    @DisplayName("200명 동시 접속 → 대기열 → 50석 경쟁")
    void 실제_티켓팅_전체_플로우_200명() throws InterruptedException {
        // Given: 200명의 사용자 생성
        int totalUsers = 200;
        int activeQueueSize = 50;  // 대기열 활성화 인원

        List<TestUser> users = createUsers(totalUsers);

        log.info("=== 티켓 오픈 시작 ===");
        log.info("총 신청자: {}명, 좌석: {}개, 활성 대기열: {}명",
                totalUsers, TOTAL_SEATS, activeQueueSize);

        // 대기열 토큰 발급
        List<String> tokens = issueTokensForUsers(users);

        // 일부만 활성화
        activateTokens(tokens.subList(0, activeQueueSize));

        // 결과 수집
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(totalUsers);

        AtomicInteger queueBlockedCount = new AtomicInteger(0);
        AtomicInteger seatQuerySuccess = new AtomicInteger(0);
        AtomicInteger tempAssignSuccess = new AtomicInteger(0);
        AtomicInteger confirmSuccess = new AtomicInteger(0);
        AtomicInteger seatCompetitionFailed = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);

        List<Long> responseTimes = new CopyOnWriteArrayList<>();
        Map<String, String> errorCategories = new ConcurrentHashMap<>();

        Instant overallStart = Instant.now();

        // When: 1000명 동시 예약 시도
        for (int i = 0; i < totalUsers; i++) {
            final TestUser user = users.get(i);
            final String token = tokens.get(i);
            final int seatNumber = (i % TOTAL_SEATS) + 1;

            taskExecutor.execute(() -> {
                long startTime = System.currentTimeMillis();
                try {
                    startSignal.await();

                    // 1. 대기열 검증
                    boolean isActive = queueUseCase.isTokenActive(token);
                    if (!isActive) {
                        queueBlockedCount.incrementAndGet();
                        errorCategories.put(user.userId.toString(), "QUEUE_BLOCKED");
                        return;
                    }

                    // 2. 좌석 조회
                    List<SeatQueryUseCase.SeatView> seats =
                            seatQueryUseCase.getSeatsStatus(concertScheduleId, LocalDate.now().plusDays(7));
                    seatQuerySuccess.incrementAndGet();

                    // 3. 좌석 임시 할당
                    ReservationUseCase.TemporaryAssignResult tempAssign =
                            reservationUseCase.temporaryAssign(
                                    new ReservationUseCase.TemporaryAssignCommand(
                                            token,
                                            concertScheduleId,
                                            seatNumber
                                    )
                            );
                    tempAssignSuccess.incrementAndGet();

                    // 4. 예약 확정
                    ReservationUseCase.ConfirmReservationResult confirmed =
                            reservationUseCase.confirmReservation(
                                    new ReservationUseCase.ConfirmReservationCommand(
                                            token,
                                            tempAssign.reservationId(),
                                            UUID.randomUUID().toString()
                                    )
                            );
                    confirmSuccess.incrementAndGet();

                    // 5. 랭킹 업데이트
                    rankingUseCase.trackReservation(concertScheduleId, 1);

                    long responseTime = System.currentTimeMillis() - startTime;
                    responseTimes.add(responseTime);

                    log.debug("성공: userId={}, seat={}, time={}ms",
                            user.userId, seatNumber, responseTime);

                } catch (QueueTokenNotActiveException | QueueTokenExpiredException e) {
                    queueBlockedCount.incrementAndGet();
                    errorCategories.put(user.userId.toString(), "QUEUE_ERROR");
                } catch (SeatAlreadyAssignedException | SeatAlreadyConfirmedException e) {
                    seatCompetitionFailed.incrementAndGet();
                    errorCategories.put(user.userId.toString(), "SEAT_TAKEN");
                    log.debug("좌석 경합 실패: userId={}, error={}", user.userId, e.getMessage());
                } catch (Exception e) {
                    otherErrors.incrementAndGet();
                    errorCategories.put(user.userId.toString(), e.getClass().getSimpleName());
                    log.debug("기타 오류: userId={}, error={}", user.userId, e.getMessage());
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        // 티켓 오픈
        Thread.sleep(100);
        log.info("\n=== 티켓 오픈! ===\n");
        startSignal.countDown();

        boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        Duration totalDuration = Duration.between(overallStart, Instant.now());

        // Then: 결과 분석
        printDetailedResults(
                totalUsers, activeQueueSize,
                queueBlockedCount.get(),
                seatQuerySuccess.get(),
                tempAssignSuccess.get(),
                confirmSuccess.get(),
                seatCompetitionFailed.get(),
                otherErrors.get(),
                responseTimes,
                totalDuration,
                errorCategories
        );

        // 검증
        assertThat(confirmSuccess.get())
                .as("확정된 예약은 좌석 수를 초과할 수 없음")
                .isLessThanOrEqualTo(TOTAL_SEATS);

        assertThat(confirmSuccess.get())
                .as("최소 1개 이상의 예약은 성공해야 함")
                .isGreaterThan(0);

        assertThat(queueBlockedCount.get())
                .as("대기열 차단은 (전체 - 활성) 인원 정도여야 함")
                .isGreaterThanOrEqualTo(totalUsers - activeQueueSize - 50);  // 여유 50
    }

    /**
     * 1000명 동시 접속 (부하 테스트용)
     *
     * ⚠️ 주의: 로컬 환경에서는 메모리 부족 발생 가능
     *
     * 극한 부하 시나리오:
     * - 1000명이 대기열 토큰 받음
     * - 100명만 활성화
     * - 50개 좌석을 놓고 경쟁
     *
     * 예상 결과:
     * - 성공: 최대 50명
     * - 대기열 차단: 900명
     * - 좌석 경합 실패: 50명
     */
    @Disabled("비동기 결제로 변경 - 별도 Kafka 통합 테스트로 대체 예정")
    @Test
    @Tag("load-test")  // 태그로 분리
    @DisplayName("1000명 동시 접속 → 극한 부하 테스트")
    void 실제_티켓팅_전체_플로우_1000명_부하테스트() throws InterruptedException {
        // Given: 1000명의 사용자 생성
        int totalUsers = 1000;
        int activeQueueSize = 100;

        List<TestUser> users = createUsers(totalUsers);

        log.info("=== 극한 부하 테스트 시작 ===");
        log.info("주의: 대량의 메모리와 DB 커넥션 사용");
        log.info("총 신청자: {}명, 좌석: {}개, 활성 대기열: {}명",
                totalUsers, TOTAL_SEATS, activeQueueSize);

        // 대기열 토큰 발급
        List<String> tokens = issueTokensForUsers(users);
        activateTokens(tokens.subList(0, activeQueueSize));

        // 결과 수집
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(totalUsers);

        AtomicInteger queueBlockedCount = new AtomicInteger(0);
        AtomicInteger seatQuerySuccess = new AtomicInteger(0);
        AtomicInteger tempAssignSuccess = new AtomicInteger(0);
        AtomicInteger confirmSuccess = new AtomicInteger(0);
        AtomicInteger seatCompetitionFailed = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);

        List<Long> responseTimes = new CopyOnWriteArrayList<>();
        Map<String, String> errorCategories = new ConcurrentHashMap<>();

        Instant overallStart = Instant.now();

        // When: 1000명 동시 예약 시도
        for (int i = 0; i < totalUsers; i++) {
            final TestUser user = users.get(i);
            final String token = tokens.get(i);
            final int seatNumber = (i % TOTAL_SEATS) + 1;

            taskExecutor.execute(() -> {
                long startTime = System.currentTimeMillis();
                try {
                    startSignal.await();

                    // 1. 대기열 검증
                    boolean isActive = queueUseCase.isTokenActive(token);
                    if (!isActive) {
                        queueBlockedCount.incrementAndGet();
                        errorCategories.put(user.userId.toString(), "QUEUE_BLOCKED");
                        return;
                    }

                    // 2. 좌석 조회
                    List<SeatQueryUseCase.SeatView> seats =
                            seatQueryUseCase.getSeatsStatus(concertScheduleId, LocalDate.now().plusDays(7));
                    seatQuerySuccess.incrementAndGet();

                    // 3. 좌석 임시 할당
                    ReservationUseCase.TemporaryAssignResult tempAssign =
                            reservationUseCase.temporaryAssign(
                                    new ReservationUseCase.TemporaryAssignCommand(
                                            token,
                                            concertScheduleId,
                                            seatNumber
                                    )
                            );
                    tempAssignSuccess.incrementAndGet();

                    // 4. 예약 확정
                    ReservationUseCase.ConfirmReservationResult confirmed =
                            reservationUseCase.confirmReservation(
                                    new ReservationUseCase.ConfirmReservationCommand(
                                            token,
                                            tempAssign.reservationId(),
                                            UUID.randomUUID().toString()
                                    )
                            );
                    confirmSuccess.incrementAndGet();

                    // 5. 랭킹 업데이트
                    rankingUseCase.trackReservation(concertScheduleId, 1);

                    long responseTime = System.currentTimeMillis() - startTime;
                    responseTimes.add(responseTime);

                } catch (QueueTokenNotActiveException | QueueTokenExpiredException e) {
                    queueBlockedCount.incrementAndGet();
                    errorCategories.put(user.userId.toString(), "QUEUE_ERROR");
                } catch (SeatAlreadyAssignedException | SeatAlreadyConfirmedException e) {
                    seatCompetitionFailed.incrementAndGet();
                    errorCategories.put(user.userId.toString(), "SEAT_TAKEN");
                } catch (Exception e) {
                    otherErrors.incrementAndGet();
                    errorCategories.put(user.userId.toString(), e.getClass().getSimpleName());
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        Thread.sleep(100);
        log.info("\n=== 티켓 오픈! ===\n");
        startSignal.countDown();

        boolean completed = completionLatch.await(120, TimeUnit.SECONDS);  // 타임아웃 증가
        assertThat(completed).isTrue();

        Duration totalDuration = Duration.between(overallStart, Instant.now());

        // Then: 결과 분석
        printDetailedResults(
                totalUsers, activeQueueSize,
                queueBlockedCount.get(),
                seatQuerySuccess.get(),
                tempAssignSuccess.get(),
                confirmSuccess.get(),
                seatCompetitionFailed.get(),
                otherErrors.get(),
                responseTimes,
                totalDuration,
                errorCategories
        );

        // 검증
        assertThat(confirmSuccess.get())
                .as("확정된 예약은 좌석 수를 초과할 수 없음")
                .isLessThanOrEqualTo(TOTAL_SEATS);

        assertThat(confirmSuccess.get())
                .as("최소 1개 이상의 예약은 성공해야 함")
                .isGreaterThan(0);

        assertThat(queueBlockedCount.get())
                .as("대기열 차단은 (전체 - 활성) 인원 정도여야 함")
                .isGreaterThanOrEqualTo(totalUsers - activeQueueSize - 50);
    }

    /**
     * 단계별 이탈 분석
     */
    @Test
    @DisplayName("단계별 이탈률 분석")
    void 사용자_파이프라인_분석_전체_플로우() throws InterruptedException {
        // Given
        int totalUsers = 100;
        List<TestUser> users = createUsers(totalUsers);
        List<String> tokens = issueTokensForUsers(users);
        activateTokens(tokens);  // 전체 활성화

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(totalUsers);

        AtomicInteger step1_queueCheck = new AtomicInteger(0);
        AtomicInteger step2_seatView = new AtomicInteger(0);
        AtomicInteger step3_tempAssign = new AtomicInteger(0);
        AtomicInteger step4_confirm = new AtomicInteger(0);

        Random random = new Random();

        // When: 사용자 행동 시뮬레이션
        for (int i = 0; i < totalUsers; i++) {
            final TestUser user = users.get(i);
            final String token = tokens.get(i);
            final int seatNumber = random.nextInt(TOTAL_SEATS) + 1;

            taskExecutor.execute(() -> {
                try {
                    startLatch.await();
                    Thread.sleep(random.nextInt(100));  // 사용자 접속 시간 차이

                    // 1. 대기열 확인 (100%)
                    boolean isActive = queueUseCase.isTokenActive(token);
                    step1_queueCheck.incrementAndGet();
                    if (!isActive) return;

                    // 2. 좌석 조회 (80% 진입)
                    if (random.nextInt(100) < 80) {
                        seatQueryUseCase.getSeatsStatus(concertScheduleId, LocalDate.now().plusDays(7));
                        step2_seatView.incrementAndGet();
                        Thread.sleep(random.nextInt(500));  // 고민 시간

                        // 3. 임시 할당 시도 (60% 진입)
                        if (random.nextInt(100) < 60) {
                            try {
                                var result = reservationUseCase.temporaryAssign(
                                        new ReservationUseCase.TemporaryAssignCommand(
                                                token, concertScheduleId, seatNumber
                                        )
                                );
                                step3_tempAssign.incrementAndGet();
                                Thread.sleep(random.nextInt(1000));  // 결제 고민

                                // 4. 예약 확정 (70% 진입)
                                if (random.nextInt(100) < 70) {
                                    reservationUseCase.confirmReservation(
                                            new ReservationUseCase.ConfirmReservationCommand(
                                                    token,
                                                    result.reservationId(),
                                                    UUID.randomUUID().toString()
                                            )
                                    );
                                    rankingUseCase.trackReservation(concertScheduleId, 1);
                                    step4_confirm.incrementAndGet();
                                }
                            } catch (Exception e) {
                            }
                        }
                    }

                } catch (Exception e) {
                    log.debug("사용자 {} 처리 중 오류: {}", user.userId, e.getMessage());
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        completionLatch.await(30, TimeUnit.SECONDS);

        // Then: 파이프라인 분석
        printPipelineAnalysis(
                totalUsers,
                step1_queueCheck.get(),
                step2_seatView.get(),
                step3_tempAssign.get(),
                step4_confirm.get()
        );

        assertThat(step1_queueCheck.get()).isEqualTo(totalUsers);
        assertThat(step2_seatView.get()).isLessThan(step1_queueCheck.get());
        assertThat(step3_tempAssign.get()).isLessThan(step2_seatView.get());
        assertThat(step4_confirm.get()).isLessThan(step3_tempAssign.get());
    }

    private List<TestUser> createUsers(int count) {
        List<TestUser> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID userId = UUID.randomUUID();
            String userName = "user_" + i + "_" + System.currentTimeMillis();

            UserJpaEntity user = new UserJpaEntity(userId, userName);
            userRepository.save(user);

            UserWalletJpaEntity wallet = new UserWalletJpaEntity(userId, 1_000_000L);
            walletRepository.save(wallet);

            users.add(new TestUser(userId, userName));
        }
        log.info("{}명의 사용자 생성 완료", count);
        return users;
    }

    private List<String> issueTokensForUsers(List<TestUser> users) {
        List<String> tokens = new ArrayList<>();
        for (TestUser user : users) {
            QueueUseCase.TokenInfo tokenInfo = queueUseCase.issueToken(
                    new QueueUseCase.IssueTokenCommand(user.userId.toString())
            );
            tokens.add(tokenInfo.token());
        }
        log.info("{}개의 대기열 토큰 발급 완료", tokens.size());
        return tokens;
    }

    private void activateTokens(List<String> tokens) {
        // MySQL 기반으로 직접 토큰 활성화
        for (String token : tokens) {
            try {
                // QueueTokenJpaEntity를 직접 조회해서 활성화
                Optional<QueueTokenJpaEntity> entityOpt =
                        queueTokenRepository.findByToken(token);

                if (entityOpt.isPresent()) {
                    QueueTokenJpaEntity entity = entityOpt.get();
                    if (entity.getStatus() == QueueTokenJpaEntity.TokenStatus.WAITING) {
                        entity.activate();  // WAITING → ACTIVE
                        queueTokenRepository.save(entity);
                        log.debug("토큰 활성화 완료: {}", token);
                    }
                } else {
                    log.warn("토큰을 찾을 수 없음: {}", token);
                }
            } catch (Exception e) {
                log.error("토큰 활성화 실패: {}", token, e);
            }
        }
        log.info("{}개의 토큰 활성화 완료", tokens.size());
    }

    private void printDetailedResults(
            int totalUsers, int activeQueue,
            int queueBlocked, int seatQuerySuccess, int tempAssignSuccess,
            int confirmSuccess, int seatFailed, int otherErrors,
            List<Long> responseTimes, Duration totalDuration,
            Map<String, String> errorCategories
    ) {
        log.info("\n");
        log.info("=".repeat(70));
        log.info("티켓팅 전체 플로우 결과 분석");
        log.info("=".repeat(70));
        log.info("");

        log.info("전체 소요 시간: {}ms ({}초)",
                totalDuration.toMillis(), totalDuration.getSeconds());
        log.info("");

        log.info("단계별 처리 현황:");
        log.info("총 시도:           {} 명", totalUsers);
        log.info("대기열 통과:       {} 명 ({}%)",
                activeQueue, activeQueue * 100 / totalUsers);
        log.info("대기열 차단:       {} 명", queueBlocked);
        log.info("좌석 조회 성공:    {} 명", seatQuerySuccess);
        log.info("임시 할당 성공:    {} 명", tempAssignSuccess);
        log.info("예약 확정 성공:    {} 명", confirmSuccess);
        log.info("좌석 경합 실패:    {} 명", seatFailed);
        log.info("기타 오류:         {} 명", otherErrors);
        log.info("");

        log.info("최종 전환율:");
        double conversionRate = confirmSuccess * 100.0 / totalUsers;
        double competitionRate = confirmSuccess * 100.0 / activeQueue;
        double seatUtilization = confirmSuccess * 100.0 / TOTAL_SEATS;
        log.info("전체 기준:     {}/{}명 = {}%",
                confirmSuccess, totalUsers, String.format("%.1f", conversionRate));
        log.info("활성 사용자 기준: {}/{}명 = {}%",
                confirmSuccess, activeQueue, String.format("%.1f", competitionRate));
        log.info("좌석 활용률:   {}/{}석 = {}%",
                confirmSuccess, TOTAL_SEATS, String.format("%.0f", seatUtilization));
        log.info("");

        if (!responseTimes.isEmpty()) {
            Collections.sort(responseTimes);
            long avg = responseTimes.stream().mapToLong(Long::longValue).sum() / responseTimes.size();
            long p50 = responseTimes.get(responseTimes.size() / 2);
            long p95 = responseTimes.get((int)(responseTimes.size() * 0.95));
            long p99 = responseTimes.get((int)(responseTimes.size() * 0.99));
            long max = responseTimes.get(responseTimes.size() - 1);

            log.info("응답 시간 분석 (성공한 요청만):");
            log.info("평균:  {} ms", avg);
            log.info("P50:   {} ms", p50);
            log.info("P95:   {} ms", p95);
            log.info("P99:   {} ms", p99);
            log.info("Max:   {} ms", max);
            log.info("");

            double actualTps = confirmSuccess * 1000.0 / totalDuration.toMillis();
            double totalTps = totalUsers * 1000.0 / totalDuration.toMillis();
            log.info("처리량 (TPS):");
            log.info("성공한 예약 TPS:  {} req/s", String.format("%.1f", actualTps));
            log.info("전체 요청 TPS:    {} req/s", String.format("%.1f", totalTps));
        }
        log.info("");

        // 오류 분류 통계
        Map<String, Long> errorStats = new HashMap<>();
        errorCategories.values().forEach(error ->
                errorStats.merge(error, 1L, Long::sum)
        );

        if (!errorStats.isEmpty()) {
            log.info("실패 원인 분석:");
            errorStats.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(entry -> log.info("   {}: {} 건", entry.getKey(), entry.getValue()));
            log.info("");
        }

        log.info("=".repeat(70));
        log.info("");
    }

    private void printPipelineAnalysis(
            int total, int step1, int step2, int step3, int step4
    ) {
        log.info("\n");
        log.info("=".repeat(70));
        log.info("사용자 파이프라인 분석");
        log.info("=".repeat(70));
        log.info("");

        log.info("단계별 전환:");
        log.info("1. 대기열 확인:   {} 명 (100.0%)", step1);
        log.info("2. 좌석 조회:     {} 명 ({:.1f}%)",
                step2, step2 * 100.0 / step1);
        log.info("3. 임시 할당:     {} 명 ({:.1f}%)",
                step3, step3 * 100.0 / step2);
        log.info("4. 예약 확정:     {} 명 ({:.1f}%)",
                step4, step4 * 100.0 / step3);
        log.info("");

        log.info("단계별 이탈률:");
        log.info("대기열 → 조회:  {}% 이탈",
                (step1 - step2) * 100 / step1);
        log.info("조회 → 할당:    {}% 이탈",
                step2 > 0 ? (step2 - step3) * 100 / step2 : 0);
        log.info("할당 → 확정:    {}% 이탈",
                step3 > 0 ? (step3 - step4) * 100 / step3 : 0);
        log.info("");

        log.info("최종 전환율:    {:.1f}% ({}/{}명)",
                step4 * 100.0 / total, step4, total);
        log.info("");
        log.info("=".repeat(70));
        log.info("");
    }

    private record TestUser(UUID userId, String userName) {}
}