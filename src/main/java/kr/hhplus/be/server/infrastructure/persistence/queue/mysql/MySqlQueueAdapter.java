package kr.hhplus.be.server.infrastructure.persistence.queue.mysql;

import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.domain.queue.model.QueueToken;
import kr.hhplus.be.server.infrastructure.persistence.queue.jpa.entity.QueueTokenJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.queue.jpa.entity.QueueTokenJpaEntity.TokenStatus;
import kr.hhplus.be.server.infrastructure.persistence.queue.jpa.repository.QueueTokenJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class MySqlQueueAdapter implements QueuePort {

    private final QueueTokenJpaRepository repository;
    private static final int MAX_ACTIVE_USERS = 100;

    @Override
    @Transactional
    public QueueToken issue(String userId) {
        // 1. 이미 활성 토큰이 있는지 확인
        Optional<QueueTokenJpaEntity> existing = repository.findByUserIdAndStatusIn(
                userId,
                Arrays.asList(TokenStatus.WAITING, TokenStatus.ACTIVE)
        );

        if (existing.isPresent()) {
            // 이미 토큰이 있으면 기존 토큰 반환
            return new QueueToken(existing.get().getToken());
        }

        // 2. 새 토큰 생성
        String token = UUID.randomUUID().toString();
        QueueTokenJpaEntity entity = new QueueTokenJpaEntity(token, userId);

        // 3. 활성 사용자 수 확인
        long activeCount = repository.countByStatus(TokenStatus.ACTIVE);

        if (activeCount < MAX_ACTIVE_USERS) {
            // 바로 활성화
            entity.activate();
            log.debug("토큰 즉시 활성화: userId={}, token={}", userId, token);
        } else {
            // 대기열에 추가
            log.debug("대기열 추가: userId={}, token={}", userId, token);
        }

        try {
            repository.save(entity);

            // 4. 대기열인 경우 순번 계산 및 업데이트
            if (entity.getStatus() == TokenStatus.WAITING) {
                updateWaitingPositions();
            }

            return new QueueToken(token);
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 유저가 토큰 발급 시도한 경우
            Optional<QueueTokenJpaEntity> retry = repository.findByUserIdAndStatusIn(
                    userId,
                    Arrays.asList(TokenStatus.WAITING, TokenStatus.ACTIVE)
            );
            return retry.map(t -> new QueueToken(t.getToken()))
                    .orElseThrow(() -> new RuntimeException("토큰 발급 실패", e));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(String token) {
        return repository.findByToken(token)
                .map(entity -> entity.getStatus() == TokenStatus.ACTIVE && !entity.isExpired())
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public String userIdOf(String token) {
        return repository.findByToken(token)
                .map(QueueTokenJpaEntity::getUserId)
                .orElse(null);
    }

    @Override
    @Transactional
    public void expire(String token) {
        repository.findByToken(token).ifPresent(entity -> {
            entity.expire();
            repository.save(entity);
            log.debug("토큰 만료 처리: token={}", token);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Long getWaitingPosition(String token) {
        Optional<QueueTokenJpaEntity> entity = repository.findByToken(token);

        if (entity.isEmpty()) {
            return null;
        }

        if (entity.get().getStatus() != TokenStatus.WAITING) {
            return null;
        }

        // 실시간으로 순번 계산 (더 정확)
        List<QueueTokenJpaEntity> waitingList = repository.findAllWaitingTokens();
        for (int i = 0; i < waitingList.size(); i++) {
            if (waitingList.get(i).getToken().equals(token)) {
                return (long) (i + 1);
            }
        }

        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public Long getActiveCount() {
        return repository.countByStatus(TokenStatus.ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public Long getWaitingCount() {
        return repository.countByStatus(TokenStatus.WAITING);
    }

    @Override
    @Transactional
    public void activateNextUsers(int count) {
        if (count <= 0) {
            return;
        }

        // 1. 만료된 활성 토큰 정리
        cleanupExpiredTokens();

        // 2. 대기 중인 토큰을 순서대로 활성화
        List<QueueTokenJpaEntity> waitingTokens = repository.findWaitingTokensForActivation(count);

        int activated = 0;
        for (QueueTokenJpaEntity token : waitingTokens) {
            long currentActive = repository.countByStatus(TokenStatus.ACTIVE);
            if (currentActive >= MAX_ACTIVE_USERS) {
                break;
            }

            token.activate();
            repository.save(token);
            activated++;
            log.debug("대기열 토큰 활성화: token={}, userId={}",
                    token.getToken(), token.getUserId());
        }

        if (activated > 0) {
            log.info("대기열에서 {}개 토큰 활성화 완료", activated);
            updateWaitingPositions();
        }
    }

    // 대기열 순번 업데이트
    private void updateWaitingPositions() {
        List<QueueTokenJpaEntity> waitingList = repository.findAllWaitingTokens();
        for (int i = 0; i < waitingList.size(); i++) {
            repository.updateWaitingPosition(waitingList.get(i).getToken(), (long) (i + 1));
        }
    }

    // 만료된 토큰 정리
    private void cleanupExpiredTokens() {
        List<QueueTokenJpaEntity> expired = repository.findExpiredActiveTokens(LocalDateTime.now());
        for (QueueTokenJpaEntity token : expired) {
            token.expire();
            repository.save(token);
            log.debug("만료된 토큰 정리: token={}", token.getToken());
        }
    }
}