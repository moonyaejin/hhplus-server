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

    // 조건부 UPDATE 메서드 추가 - 잔액 차감
    @Modifying
    @Query("""
        UPDATE UserWalletJpaEntity w 
        SET w.balance = w.balance - :amount, 
            w.version = w.version + 1 
        WHERE w.userId = :userId 
          AND w.balance >= :amount
    """)

    int decreaseBalance(@Param("userId") UUID userId, @Param("amount") long amount);

    // 조건부 UPDATE 메서드 추가 - 잔액 증가
    @Modifying
    @Query("""
        UPDATE UserWalletJpaEntity w 
        SET w.balance = w.balance + :amount,
            w.version = w.version + 1
        WHERE w.userId = :userId
    """)
    int increaseBalance(@Param("userId") UUID userId, @Param("amount") long amount);
}
