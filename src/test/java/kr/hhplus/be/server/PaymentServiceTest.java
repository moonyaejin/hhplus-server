package kr.hhplus.be.server;

import kr.hhplus.be.server.application.port.in.PaymentUseCase.*;
import kr.hhplus.be.server.application.port.out.WalletPort;
import kr.hhplus.be.server.application.service.PaymentService;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.payment.Balance;
import kr.hhplus.be.server.domain.payment.Wallet;
import kr.hhplus.be.server.domain.payment.WalletId;
import kr.hhplus.be.server.infrastructure.redis.lock.RedisDistributedLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PaymentService 테스트 - 개선된 구조
 *
 * @Nested를 사용해 락이 필요한 테스트와 필요없는 테스트를 분리
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private WalletPort walletPort;

    @Mock
    private RedisDistributedLock distributedLock;

    @Mock
    private TransactionTemplate transactionTemplate;

    private PaymentService paymentService;

    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String IDEMPOTENCY_KEY = "test-idempotency-key";

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(walletPort, distributedLock, transactionTemplate);
    }

    /**
     * 분산락을 사용하는 정상 플로우 테스트
     */
    @Nested
    @DisplayName("정상 플로우 (분산락 사용)")
    class NormalFlowWithLock {

        @BeforeEach
        void setUpLockMocks() {
            // 이 그룹의 테스트에서만 락 mock 설정
            when(distributedLock.executeWithLock(anyString(), anyLong(), anyInt(), anyLong(), any()))
                    .thenAnswer(invocation -> {
                        var supplier = invocation.getArgument(4, java.util.function.Supplier.class);
                        return supplier.get();
                    });

            when(transactionTemplate.execute(any()))
                    .thenAnswer(invocation -> {
                        var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                        return callback.doInTransaction(null);
                    });
        }

        @Test
        @DisplayName("포인트 충전 - 성공")
        void charge_Success() {
            // given
            ChargeCommand command = new ChargeCommand(USER_ID, 50_000L, IDEMPOTENCY_KEY);

            UserId userId = UserId.ofString(USER_ID);
            Wallet wallet = Wallet.restore(
                    new WalletId(USER_ID + "_wallet"),
                    userId,
                    new Balance(30_000L),
                    LocalDateTime.now(),
                    0L
            );

            when(walletPort.isIdempotencyKeyUsed(any(), eq(IDEMPOTENCY_KEY))).thenReturn(false);
            when(walletPort.findByUserId(any())).thenReturn(Optional.of(wallet));

            // when
            BalanceResult result = paymentService.charge(command);

            // then
            assertThat(result.balance()).isEqualTo(80_000L);
            verify(walletPort).save(any(Wallet.class));
            verify(walletPort).saveLedgerEntry(any(UserId.class), eq(50_000L), eq("TOP_UP"), eq(IDEMPOTENCY_KEY));
        }

        @Test
        @DisplayName("결제 처리 - 성공")
        void pay_Success() {
            // given
            PaymentCommand command = new PaymentCommand(USER_ID, 30_000L, IDEMPOTENCY_KEY);

            UserId userId = UserId.ofString(USER_ID);
            Wallet wallet = Wallet.restore(
                    new WalletId(USER_ID + "_wallet"),
                    userId,
                    new Balance(100_000L),
                    LocalDateTime.now(),
                    0L
            );

            when(walletPort.isIdempotencyKeyUsed(any(), eq(IDEMPOTENCY_KEY))).thenReturn(false);
            when(walletPort.findByUserId(any())).thenReturn(Optional.of(wallet));

            // when
            BalanceResult result = paymentService.pay(command);

            // then
            assertThat(result.balance()).isEqualTo(70_000L);
            verify(walletPort).save(any(Wallet.class));
        }

        @Test
        @DisplayName("포인트 충전 - 지갑이 없으면 실패")
        void charge_WalletNotFound() {
            // given
            ChargeCommand command = new ChargeCommand(USER_ID, 50_000L, IDEMPOTENCY_KEY);

            when(walletPort.isIdempotencyKeyUsed(any(), eq(IDEMPOTENCY_KEY))).thenReturn(false);
            when(walletPort.findByUserId(any())).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> paymentService.charge(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("지갑을 찾을 수 없습니다");
        }

        @Test
        @DisplayName("결제 처리 - 잔액 부족")
        void pay_InsufficientBalance() {
            // given
            PaymentCommand command = new PaymentCommand(USER_ID, 50_000L, IDEMPOTENCY_KEY);

            UserId userId = UserId.ofString(USER_ID);
            Wallet wallet = Wallet.restore(
                    new WalletId(USER_ID + "_wallet"),
                    userId,
                    new Balance(30_000L),
                    LocalDateTime.now(),
                    0L
            );

            when(walletPort.isIdempotencyKeyUsed(any(), eq(IDEMPOTENCY_KEY))).thenReturn(false);
            when(walletPort.findByUserId(any())).thenReturn(Optional.of(wallet));

            // when & then
            assertThatThrownBy(() -> paymentService.pay(command))
                    .hasMessageContaining("잔액이 부족합니다");

            verify(walletPort, never()).save(any());
        }
    }

    /**
     * 멱등성 체크로 인한 Early Return 테스트
     * - 락 안에서 멱등성 체크 후 early return
     */
    @Nested
    @DisplayName("멱등성 체크 (분산락 미사용)")
    class IdempotencyCheck {

        @BeforeEach
        void setUpLockMocks() {
            // 멱등성 체크도 락 안에서
            when(distributedLock.executeWithLock(anyString(), anyLong(), anyInt(), anyLong(), any()))
                    .thenAnswer(invocation -> {
                        var supplier = invocation.getArgument(4, java.util.function.Supplier.class);
                        return supplier.get();
                    });
        }

        @Test
        @DisplayName("포인트 충전 - 멱등성 보장")
        void charge_Idempotency() {
            // given
            ChargeCommand command = new ChargeCommand(USER_ID, 50_000L, IDEMPOTENCY_KEY);

            when(walletPort.isIdempotencyKeyUsed(any(), eq(IDEMPOTENCY_KEY))).thenReturn(true);
            when(walletPort.balanceOf(USER_ID)).thenReturn(80_000L);

            // when
            BalanceResult result = paymentService.charge(command);

            // then
            assertThat(result.balance()).isEqualTo(80_000L);
            verify(walletPort, never()).save(any());
            verify(walletPort, never()).saveLedgerEntry(any(), anyLong(), any(), any());

            // 락은 사용되지만, 멱등성 체크로 인해 실제 작업은 수행되지 않음
            verify(distributedLock).executeWithLock(anyString(), anyLong(), anyInt(), anyLong(), any());
            verifyNoInteractions(transactionTemplate);  // 트랜잭션은 시작되지 않음
        }

        @Test
        @DisplayName("결제 처리 - 멱등성 보장")
        void pay_Idempotency() {
            // given
            PaymentCommand command = new PaymentCommand(USER_ID, 30_000L, IDEMPOTENCY_KEY);

            when(walletPort.isIdempotencyKeyUsed(any(), eq(IDEMPOTENCY_KEY))).thenReturn(true);
            when(walletPort.balanceOf(USER_ID)).thenReturn(70_000L);

            // when
            BalanceResult result = paymentService.pay(command);

            // then
            assertThat(result.balance()).isEqualTo(70_000L);
            verify(walletPort, never()).save(any());

            // 락은 사용되지만, 멱등성 체크로 인해 실제 작업은 수행되지 않음
            verify(distributedLock).executeWithLock(anyString(), anyLong(), anyInt(), anyLong(), any());
            verifyNoInteractions(transactionTemplate);  // 트랜잭션은 시작되지 않음
        }
    }

    /**
     * 조회 전용 테스트
     * - 분산락을 사용하지 않음
     */
    @Nested
    @DisplayName("조회 기능 (분산락 미사용)")
    class QueryOperations {

        @Test
        @DisplayName("잔액 조회 - 성공")
        void getBalance_Success() {
            // given
            BalanceQuery query = new BalanceQuery(USER_ID);
            when(walletPort.balanceOf(USER_ID)).thenReturn(100_000L);

            // when
            BalanceResult result = paymentService.getBalance(query);

            // then
            assertThat(result.balance()).isEqualTo(100_000L);

            // 분산락이 호출되지 않음을 명시적으로 검증
            verifyNoInteractions(distributedLock);
            verifyNoInteractions(transactionTemplate);
        }
    }

    /**
     * 도메인 검증 테스트
     */
    @Nested
    @DisplayName("도메인 검증")
    class DomainValidation {

        @BeforeEach
        void setUpLockMocks() {
            when(distributedLock.executeWithLock(anyString(), anyLong(), anyInt(), anyLong(), any()))
                    .thenAnswer(invocation -> {
                        var supplier = invocation.getArgument(4, java.util.function.Supplier.class);
                        return supplier.get();
                    });

            when(transactionTemplate.execute(any()))
                    .thenAnswer(invocation -> {
                        var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                        return callback.doInTransaction(null);
                    });
        }

        @Test
        @DisplayName("음수 금액으로 충전 시도시 실패")
        void charge_NegativeAmount() {
            // given
            ChargeCommand command = new ChargeCommand(USER_ID, -10_000L, IDEMPOTENCY_KEY);

            UserId userId = UserId.ofString(USER_ID);
            Wallet wallet = Wallet.restore(
                    new WalletId(USER_ID + "_wallet"),
                    userId,
                    new Balance(30_000L),
                    LocalDateTime.now(),
                    0L
            );

            when(walletPort.isIdempotencyKeyUsed(any(), eq(IDEMPOTENCY_KEY))).thenReturn(false);
            when(walletPort.findByUserId(any())).thenReturn(Optional.of(wallet));

            // when & then
            assertThatThrownBy(() -> paymentService.charge(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("충전 금액은 0보다 커야 합니다");

            verify(walletPort, never()).save(any());
        }

        @Test
        @DisplayName("0원 결제 시도시 실패")
        void pay_ZeroAmount() {
            // given
            PaymentCommand command = new PaymentCommand(USER_ID, 0L, IDEMPOTENCY_KEY);

            UserId userId = UserId.ofString(USER_ID);
            Wallet wallet = Wallet.restore(
                    new WalletId(USER_ID + "_wallet"),
                    userId,
                    new Balance(100_000L),
                    LocalDateTime.now(),
                    0L
            );

            when(walletPort.isIdempotencyKeyUsed(any(), eq(IDEMPOTENCY_KEY))).thenReturn(false);
            when(walletPort.findByUserId(any())).thenReturn(Optional.of(wallet));

            // when & then
            assertThatThrownBy(() -> paymentService.pay(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("사용 금액은 0보다 커야 합니다");

            verify(walletPort, never()).save(any());
        }
    }
}