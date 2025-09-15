package kr.hhplus.be.server;

import kr.hhplus.be.server.application.service.WalletService;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWalletJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.WalletLedgerJpaRepository;
import kr.hhplus.be.server.web.wallet.dto.ChargeRequest;
import kr.hhplus.be.server.web.wallet.dto.ChargeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;  // JUnit 5
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;  // static import 추가
import static org.mockito.ArgumentMatchers.any;           // static import 추가
import static org.mockito.Mockito.*;                      // static import 추가

@ExtendWith(MockitoExtension.class)
public class WalletServiceTest {  // public 추가

    @Mock
    private UserWalletJpaRepository walletRepository;

    @Mock
    private WalletLedgerJpaRepository ledgerRepository;

    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(walletRepository, ledgerRepository);
    }

    @Test  // JUnit 5의 @Test
    @DisplayName("포인트 충전 - 성공")
    public void charge_Success() {  // public 추가
        // given
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");  // 유효한 UUID
        ChargeRequest request = new ChargeRequest(userId.toString(), 50000L, "key");
        UserWalletJpaEntity wallet = new UserWalletJpaEntity(userId, 10000L);

        when(walletRepository.findForUpdate(any())).thenReturn(Optional.of(wallet));
        when(ledgerRepository.existsByUserIdAndIdempotencyKey(any(), any())).thenReturn(false);

        // when
        ChargeResponse response = walletService.charge(request);

        // then
        assertThat(response.balance()).isEqualTo(60000L);
        verify(walletRepository).save(any());
        verify(ledgerRepository).save(any());
    }

    @Test
    @DisplayName("포인트 충전 - 멱등성 보장")
    public void charge_Idempotency() {
        // given
        UUID userId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        ChargeRequest request = new ChargeRequest(userId.toString(), 50000L, "duplicate-key");
        UserWalletJpaEntity wallet = new UserWalletJpaEntity(userId, 60000L);

        // 이미 처리된 키
        when(ledgerRepository.existsByUserIdAndIdempotencyKey(userId, "duplicate-key")).thenReturn(true);
        when(walletRepository.findById(userId)).thenReturn(Optional.of(wallet));

        // when
        ChargeResponse response = walletService.charge(request);

        // then
        assertThat(response.balance()).isEqualTo(60000L);  // 기존 잔액 반환
        verify(walletRepository, never()).save(any());     // 저장하지 않음
        verify(ledgerRepository, never()).save(any());      // 원장 기록하지 않음
    }

    @Test
    @DisplayName("잔액 조회 - 성공")
    public void getBalance_Success() {
        // given
        String userId = "550e8400-e29b-41d4-a716-446655440000";
        UUID uid = UUID.fromString(userId);
        UserWalletJpaEntity wallet = new UserWalletJpaEntity(uid, 100000L);

        when(walletRepository.findById(uid)).thenReturn(Optional.of(wallet));

        // when
        long balance = walletService.getBalance(userId);

        // then
        assertThat(balance).isEqualTo(100000L);
    }

    @Test
    @DisplayName("잔액 조회 - 지갑 없는 경우")
    public void getBalance_WalletNotFound() {
        // given
        String userId = "550e8400-e29b-41d4-a716-446655440000";
        UUID uid = UUID.fromString(userId);

        when(walletRepository.findById(uid)).thenReturn(Optional.empty());

        // when
        long balance = walletService.getBalance(userId);

        // then
        assertThat(balance).isEqualTo(0L);  // 기본값 0 반환
    }
}