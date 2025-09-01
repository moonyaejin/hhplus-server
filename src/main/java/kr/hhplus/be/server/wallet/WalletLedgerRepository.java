package kr.hhplus.be.server.wallet;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface WalletLedgerRepository extends JpaRepository<WalletLedger, Long> {
    boolean existsByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
