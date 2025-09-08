package kr.hhplus.be.server;

import kr.hhplus.be.server.domain.reservation.model.exception.InsufficientBalance;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.adapter.WalletJpaAdapter;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWallet;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletRepository;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.WalletLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

public class WalletJpaAdapterTest {

    private UserWalletRepository userWalletRepository;
    private WalletLedgerRepository walletLedgerRepository;
    private WalletJpaAdapter walletJpaAdapter;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userWalletRepository = mock(UserWalletRepository.class);
        walletLedgerRepository = mock(WalletLedgerRepository.class);
        walletJpaAdapter = new WalletJpaAdapter(userWalletRepository, walletLedgerRepository);
    }

    @Test
    void 결제시_잔액부족이면_예외가_발생한다() {
        // given
        UserWallet entity = new UserWallet(userId, 300L);
        when(userWalletRepository.findById(userId)).thenReturn(Optional.of(entity));

        // when & then
        assertThatThrownBy(() -> walletJpaAdapter.pay(userId.toString(), 500L, "idem-3"))
                .isInstanceOf(InsufficientBalance.class)
                .hasMessageContaining("insufficient balance");
    }
}
