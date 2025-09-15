package kr.hhplus.be.server;

import kr.hhplus.be.server.application.port.in.PaymentUseCase.*;
import kr.hhplus.be.server.application.port.out.WalletPort;
import kr.hhplus.be.server.application.service.PaymentService;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.payment.Balance;
import kr.hhplus.be.server.domain.payment.Wallet;
import kr.hhplus.be.server.domain.payment.WalletId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private WalletPort walletPort;

    private PaymentService paymentService;

    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String IDEMPOTENCY_KEY = "test-idempotency-key";

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(walletPort);
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
        assertThat(result.balance()).isEqualTo(80_000L);  // 30,000 + 50,000

        // 검증: 지갑 저장
        verify(walletPort).save(any(Wallet.class));

        // 검증: 원장 기록
        ArgumentCaptor<UserId> userIdCaptor = ArgumentCaptor.forClass(UserId.class);
        ArgumentCaptor<Long> amountCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);

        verify(walletPort).saveLedgerEntry(
                userIdCaptor.capture(),
                amountCaptor.capture(),
                reasonCaptor.capture(),
                eq(IDEMPOTENCY_KEY)
        );

        assertThat(amountCaptor.getValue()).isEqualTo(50_000L);
        assertThat(reasonCaptor.getValue()).isEqualTo("TOP_UP");
    }

    @Test
    @DisplayName("포인트 충전 - 멱등성 보장")
    void charge_Idempotency() {
        // given
        ChargeCommand command = new ChargeCommand(USER_ID, 50_000L, IDEMPOTENCY_KEY);

        // 이미 처리된 키
        when(walletPort.isIdempotencyKeyUsed(any(), eq(IDEMPOTENCY_KEY))).thenReturn(true);
        when(walletPort.balanceOf(USER_ID)).thenReturn(80_000L);

        // when
        BalanceResult result = paymentService.charge(command);

        // then
        assertThat(result.balance()).isEqualTo(80_000L);

        // 검증: 지갑 저장하지 않음
        verify(walletPort, never()).save(any());
        verify(walletPort, never()).saveLedgerEntry(any(), anyLong(), any(), any());
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
        assertThat(result.balance()).isEqualTo(70_000L);  // 100,000 - 30,000

        // 검증: 지갑 저장
        verify(walletPort).save(any(Wallet.class));

        // 검증: 원장 기록 (결제는 음수로 기록)
        ArgumentCaptor<Long> amountCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);

        verify(walletPort).saveLedgerEntry(
                any(UserId.class),
                amountCaptor.capture(),
                reasonCaptor.capture(),
                eq(IDEMPOTENCY_KEY)
        );

        assertThat(amountCaptor.getValue()).isEqualTo(-30_000L);
        assertThat(reasonCaptor.getValue()).isEqualTo("PAYMENT");
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
                new Balance(30_000L),  // 잔액 부족
                LocalDateTime.now(),
                0L
        );

        when(walletPort.isIdempotencyKeyUsed(any(), eq(IDEMPOTENCY_KEY))).thenReturn(false);
        when(walletPort.findByUserId(any())).thenReturn(Optional.of(wallet));

        // when & then
        assertThatThrownBy(() -> paymentService.pay(command))
                .hasMessageContaining("잔액이 부족합니다");

        // 검증: 지갑 저장하지 않음
        verify(walletPort, never()).save(any());
        verify(walletPort, never()).saveLedgerEntry(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("결제 처리 - 멱등성 보장")
    void pay_Idempotency() {
        // given
        PaymentCommand command = new PaymentCommand(USER_ID, 30_000L, IDEMPOTENCY_KEY);

        // 이미 처리된 키
        when(walletPort.isIdempotencyKeyUsed(any(), eq(IDEMPOTENCY_KEY))).thenReturn(true);
        when(walletPort.balanceOf(USER_ID)).thenReturn(70_000L);

        // when
        BalanceResult result = paymentService.pay(command);

        // then
        assertThat(result.balance()).isEqualTo(70_000L);

        // 검증: 지갑 저장하지 않음
        verify(walletPort, never()).save(any());
        verify(walletPort, never()).saveLedgerEntry(any(), anyLong(), any(), any());
    }

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

        // 검증: 지갑 저장하지 않음
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

        // 검증: 지갑 저장하지 않음
        verify(walletPort, never()).save(any());
    }
}