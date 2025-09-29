package kr.hhplus.be.server.concurrency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조건부 UPDATE 방식 동시성 테스트
 * - DB 레벨 조건부 UPDATE (WHERE balance >= amount)
 * - 단일 쿼리로 원자적 처리
 */
@TestPropertySource(properties = {
        "app.payment.use-conditional-update=true"
})
@DisplayName("[조건부 UPDATE 방식] 동시성 제어 테스트")
class ConditionalUpdateConcurrencyTest extends BaseConcurrencyTest {

    @Test
    @DisplayName("좌석 동시 예약 - 1명만 성공")
    void concurrent_seat_reservation() throws InterruptedException {
        testConcurrentSeatReservation();
    }

    @Test
    @DisplayName("잔액 동시 차감 - 음수 방지 (조건부 UPDATE)")
    void concurrent_balance_deduction() throws InterruptedException {
        testConcurrentBalanceDeduction();
    }

    @Test
    @DisplayName("타임아웃 후 재점유 가능")
    void timeout_release() throws InterruptedException {
        testTimeoutRelease();
    }

    @Override
    protected void assertSeatReservationResult(int success, int fail) {
        assertThat(success).isEqualTo(1);
        assertThat(fail).isEqualTo(99);
    }

    @Override
    protected void assertBalanceDeductionResult(int success, int fail, long finalBalance) {
        // 조건부 UPDATE 방식: DB 레벨에서 원자적 처리
        assertThat(success).isEqualTo(10);
        assertThat(fail).isEqualTo(10);
        assertThat(finalBalance).isEqualTo(0L);
    }

    @Override
    protected void assertTimeoutResult(boolean hold1, boolean hold2, boolean hold3) {
        assertThat(hold1).isTrue();
        assertThat(hold2).isFalse();
        assertThat(hold3).isTrue();
    }
}