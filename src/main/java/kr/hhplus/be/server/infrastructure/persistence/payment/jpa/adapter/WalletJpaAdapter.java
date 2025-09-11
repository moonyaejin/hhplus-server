package kr.hhplus.be.server.infrastructure.persistence.payment.jpa.adapter;

import kr.hhplus.be.server.application.port.out.WalletPort;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.payment.Wallet;
import kr.hhplus.be.server.domain.payment.WalletId;
import kr.hhplus.be.server.domain.payment.Balance;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWalletJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.WalletLedgerJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.WalletLedgerJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 지갑 JPA 어댑터
 * - 순수한 데이터 접근만 담당
 * - 도메인 로직 제거
 * - 트랜잭션 관리는 애플리케이션 서비스에서
 */
@Component
@Primary
@RequiredArgsConstructor
public class WalletJpaAdapter implements WalletPort {

    private final UserWalletJpaRepository walletRepo;
    private final WalletLedgerJpaRepository ledgerRepo;

    @Override
    public Optional<Wallet> findByUserId(UserId userId) {
        return walletRepo.findById(userId.asUUID())
                .map(this::toDomainWallet);
    }

    @Override
    public long balanceOf(String userId) {
        UUID uid = UUID.fromString(userId);
        return walletRepo.findById(uid)
                .map(UserWalletJpaEntity::getBalance)
                .orElseThrow(() -> new IllegalStateException("지갑을 찾을 수 없습니다: " + userId));
    }

    @Override
    public void save(Wallet wallet) {
        UserWalletJpaEntity entity = toJpaEntity(wallet);
        walletRepo.save(entity);
    }

    @Override
    public boolean isIdempotencyKeyUsed(UserId userId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return false;
        }
        return ledgerRepo.existsByUserIdAndIdempotencyKey(userId.asUUID(), idempotencyKey);
    }

    @Override
    public void saveLedgerEntry(UserId userId, long amount, String reason, String idempotencyKey) {
        WalletLedgerJpaEntity ledgerEntry = new WalletLedgerJpaEntity(
                userId.asUUID(),
                amount,
                reason,
                idempotencyKey
        );
        ledgerRepo.save(ledgerEntry);
    }

    // === Private Helper Methods ===

    private Wallet toDomainWallet(UserWalletJpaEntity entity) {
        WalletId walletId = new WalletId(entity.getUserId().toString() + "_wallet");
        UserId userId = UserId.of(entity.getUserId());
        Balance balance = new Balance(entity.getBalance());
        LocalDateTime createdAt = LocalDateTime.now(); // 실제로는 entity에서 가져와야 함

        return Wallet.restore(walletId, userId, balance, createdAt, entity.getVersion());
    }

    private UserWalletJpaEntity toJpaEntity(Wallet wallet) {
        // 기존 엔티티 조회 후 업데이트
        UserWalletJpaEntity entity = walletRepo.findById(wallet.getUserId().asUUID())
                .orElse(new UserWalletJpaEntity(
                        wallet.getUserId().asUUID(),
                        wallet.getBalance().amount()
                ));

        entity.setBalance(wallet.getBalance().amount());
        return entity;
    }
}