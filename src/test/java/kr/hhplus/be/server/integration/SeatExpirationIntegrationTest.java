package kr.hhplus.be.server.integration;

import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import kr.hhplus.be.server.domain.reservation.SeatIdentifier;
import kr.hhplus.be.server.domain.reservation.SeatNumber;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.SeatHoldJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좌석 만료 통합 테스트
 *
 * CI 환경에서는 GitHub Actions의 MySQL 서비스를 사용
 * 로컬 환경에서는 application-test.yml의 설정 사용
 */
@SpringBootTest
@ActiveProfiles("test")
class SeatExpirationIntegrationTest {

    @Autowired
    private SeatHoldPort seatHoldPort;

    @Autowired
    private SeatHoldJpaRepository seatHoldRepository;

    @BeforeEach
    void setUp() {
        // 테스트 전 모든 데이터 정리
        seatHoldRepository.deleteAll();
        seatHoldRepository.flush();
    }

    @AfterEach
    void tearDown() {
        // 테스트 후 모든 데이터 정리
        seatHoldRepository.deleteAll();
        seatHoldRepository.flush();
    }

    /**
     * 시나리오: 결제하지 않은 사용자의 좌석이 다시 예약 가능해지는 상황
     *
     * Given: User1이 좌석을 1초간만 점유
     * When:
     * - 즉시: User2 예약 시도 → 실패
     * - 2초 후: User2 재시도 → 성공
     * Then: 만료된 좌석이 다시 예약 가능함
     *
     * 검증: 좌석 점유 TTL 메커니즘과 자원 재활용
     */
    @Test
    @DisplayName("만료된 좌석은 다시 예약 가능해야 한다")
    void expiredSeatCanBeReservedAgain() throws InterruptedException {
        // Given
        SeatIdentifier seat = new SeatIdentifier(
                new ConcertScheduleId(1L),
                new SeatNumber(10)
        );
        String user1Id = UUID.randomUUID().toString();
        String user2Id = UUID.randomUUID().toString();
        UserId user1 = UserId.ofString(user1Id);
        UserId user2 = UserId.ofString(user2Id);

        // When 1: 첫 번째 사용자 점유
        boolean firstHold = seatHoldPort.tryHold(seat, user1, Duration.ofSeconds(1));
        assertThat(firstHold).isTrue();

        // When 2: 즉시 시도 - 실패
        boolean immediateSecondHold = seatHoldPort.tryHold(seat, user2, Duration.ofMinutes(5));
        assertThat(immediateSecondHold).isFalse();

        // When 3: 만료 대기
        Thread.sleep(2000);

        // When 4: 만료 후 다시 시도 - 성공
        boolean afterExpiryHold = seatHoldPort.tryHold(seat, user2, Duration.ofMinutes(5));
        assertThat(afterExpiryHold).isTrue();

        // Then: 검증
        boolean isHeldByUser2 = seatHoldPort.isHeldBy(seat, user2);
        assertThat(isHeldByUser2).isTrue();
    }

    /**
     * 시나리오: 다른 사용자가 점유 중인 좌석 예약 시도
     *
     * Given: User1이 5분간 좌석 점유 중
     * When: User2가 같은 좌석 예약 시도
     * Then: 점유 시간이 남아있는 동안은 실패
     *
     * 검증: 점유 시간 동안의 배타적 접근 보장
     */
    @Test
    @DisplayName("만료 시간 전까지는 다른 사용자가 예약할 수 없다")
    void cannotReserveBeforeExpiration() {
        // Given - UUID 형식 사용
        SeatIdentifier seat = new SeatIdentifier(
                new ConcertScheduleId(2L),
                new SeatNumber(20)
        );
        String user1Id = UUID.randomUUID().toString();
        String user2Id = UUID.randomUUID().toString();
        UserId user1 = UserId.ofString(user1Id);
        UserId user2 = UserId.ofString(user2Id);

        // When 1: 첫 번째 사용자가 5분간 점유
        boolean firstHold = seatHoldPort.tryHold(seat, user1, Duration.ofMinutes(5));
        assertThat(firstHold).isTrue();

        // When 2: 두 번째 사용자 시도 - 실패해야 함
        boolean secondHold = seatHoldPort.tryHold(seat, user2, Duration.ofMinutes(5));
        assertThat(secondHold).isFalse();

        // When 3: 첫 번째 사용자가 여전히 점유 중
        boolean stillHeldByUser1 = seatHoldPort.isHeldBy(seat, user1);

        // Then
        assertThat(firstHold).isTrue();
        assertThat(secondHold).isFalse();
        assertThat(stillHeldByUser1).isTrue();
    }
}