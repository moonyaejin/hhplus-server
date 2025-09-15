package kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository;

import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWalletJpaEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;

public interface UserWalletJpaRepository extends JpaRepository<UserWalletJpaEntity, UUID> {

    // 결제/충전 시 동시성 제어용 (행 잠금)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from UserWalletJpaEntity w where w.userId = :userId")
    Optional<UserWalletJpaEntity> findForUpdate(@Param("userId") UUID userId);
}
