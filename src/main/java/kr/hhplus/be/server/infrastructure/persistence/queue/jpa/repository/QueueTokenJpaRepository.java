package kr.hhplus.be.server.infrastructure.persistence.queue.jpa.repository;

import kr.hhplus.be.server.infrastructure.persistence.queue.jpa.entity.QueueTokenJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.queue.jpa.entity.QueueTokenJpaEntity.TokenStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface QueueTokenJpaRepository extends JpaRepository<QueueTokenJpaEntity, Long> {

    Optional<QueueTokenJpaEntity> findByToken(String token);

    Optional<QueueTokenJpaEntity> findByUserIdAndStatusIn(
            String userId,
            List<TokenStatus> statuses
    );

    // 활성 토큰 수 조회
    long countByStatus(TokenStatus status);

    // 대기 중인 토큰을 순서대로 조회 (활성화 대상)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM QueueTokenJpaEntity t " +
            "WHERE t.status = 'WAITING' " +
            "ORDER BY t.issuedAt ASC")
    List<QueueTokenJpaEntity> findWaitingTokensForActivation(@Param("limit") int limit);

    // 대기열 순번 업데이트
    @Modifying
    @Query("UPDATE QueueTokenJpaEntity t " +
            "SET t.waitingPosition = :position " +
            "WHERE t.token = :token")
    void updateWaitingPosition(@Param("token") String token, @Param("position") Long position);

    // 만료된 활성 토큰 조회
    @Query("SELECT t FROM QueueTokenJpaEntity t " +
            "WHERE t.status = 'ACTIVE' " +
            "AND t.expiresAt < :now")
    List<QueueTokenJpaEntity> findExpiredActiveTokens(@Param("now") LocalDateTime now);

    // 대기 중인 모든 토큰 (순번 계산용)
    @Query("SELECT t FROM QueueTokenJpaEntity t " +
            "WHERE t.status = 'WAITING' " +
            "ORDER BY t.issuedAt ASC")
    List<QueueTokenJpaEntity> findAllWaitingTokens();

    // 오래된 만료/사용 토큰 삭제 - expiresAt 기준으로 수정
    @Modifying
    @Query("DELETE FROM QueueTokenJpaEntity q " +
            "WHERE q.status IN ('EXPIRED', 'USED') " +
            "AND q.expiresAt < :before")
    int deleteOldExpiredTokens(@Param("before") LocalDateTime before);
}