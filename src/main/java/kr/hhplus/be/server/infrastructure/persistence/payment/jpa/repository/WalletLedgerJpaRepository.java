package kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository;

import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.WalletLedgerJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface WalletLedgerJpaRepository extends JpaRepository<WalletLedgerJpaEntity, Long> {
    boolean existsByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
