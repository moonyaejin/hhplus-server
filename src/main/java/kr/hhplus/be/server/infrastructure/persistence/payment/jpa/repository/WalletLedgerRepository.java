package kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository;

import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.WalletLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface WalletLedgerRepository extends JpaRepository<WalletLedger, Long> {
    boolean existsByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
