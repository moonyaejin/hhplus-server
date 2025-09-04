package kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository;

import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWallet;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface UserWalletRepository extends JpaRepository<UserWallet, UUID> {

    // 결제/충전 시 동시성 제어용 (행 잠금)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from UserWallet w where w.userId = :userId")
    Optional<UserWallet> findForUpdate(@Param("userId") UUID userId);
}
