package kr.hhplus.be.server;

import kr.hhplus.be.server.domain.reservation.model.exception.InsufficientBalance;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.adapter.WalletJpaAdapter;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWalletJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.WalletLedgerJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

public class WalletJpaAdapterTest {

    private UserWalletJpaRepository userWalletJpaRepository;
    private WalletLedgerJpaRepository walletLedgerJpaRepository;
    private WalletJpaAdapter walletJpaAdapter;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userWalletJpaRepository = mock(UserWalletJpaRepository.class);
        walletLedgerJpaRepository = mock(WalletLedgerJpaRepository.class);
        walletJpaAdapter = new WalletJpaAdapter(userWalletJpaRepository, walletLedgerJpaRepository);
    }

    @Test
    void 결제시_잔액부족이면_예외가_발생한다() {
        // given
        UserWalletJpaEntity entity = new UserWalletJpaEntity(userId, 300L);
        when(userWalletJpaRepository.findById(userId)).thenReturn(Optional.of(entity));

        // when & then
        assertThatThrownBy(() -> walletJpaAdapter.pay(userId.toString(), 500L, "idem-3"))
                .isInstanceOf(InsufficientBalance.class)
                .hasMessageContaining("insufficient balance");
    }
}
