package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.QueueUseCase;
import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.domain.queue.model.QueueToken;
import kr.hhplus.be.server.infrastructure.persistence.queue.redis.RedisQueueAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class QueueService implements QueueUseCase {

    private final QueuePort queuePort;
    private static final int TOKEN_TTL_MINUTES = 10;

    @Override
    public TokenInfo issueToken(IssueTokenCommand command) {
        QueueToken token = queuePort.issue(command.userId());

        // 활성화 여부 확인
        boolean isActive = queuePort.isActive(token.value());

        // 대기 순번 조회 (활성화되지 않은 경우)
        Long waitingNumber = 0L;
        String status = "ACTIVE";

        if (!isActive) {
            RedisQueueAdapter redisAdapter = (RedisQueueAdapter) queuePort;
            waitingNumber = redisAdapter.getWaitingPosition(token.value());
            status = "WAITING";
        }

        return new TokenInfo(
                token.value(),
                command.userId(),
                status,
                waitingNumber != null ? waitingNumber : 0L,
                LocalDateTime.now().plusMinutes(TOKEN_TTL_MINUTES)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public TokenInfo getTokenInfo(String token) {
        boolean isActive = queuePort.isActive(token);

        if (!isActive) {
            // 대기 중인지 확인
            RedisQueueAdapter redisAdapter = (RedisQueueAdapter) queuePort;
            Long position = redisAdapter.getWaitingPosition(token);

            if (position == null) {
                throw new RuntimeException("유효하지 않거나 만료된 토큰입니다");
            }

            String userId = queuePort.userIdOf(token);
            return new TokenInfo(
                    token,
                    userId,
                    "WAITING",
                    position,
                    null // 대기 중인 토큰은 만료 시간 없음
            );
        }

        String userId = queuePort.userIdOf(token);
        return new TokenInfo(
                token,
                userId,
                "ACTIVE",
                0L,
                LocalDateTime.now().plusMinutes(TOKEN_TTL_MINUTES)
        );
    }

    @Override
    public void expireToken(String token) {
        queuePort.expire(token);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isTokenActive(String token) {
        return queuePort.isActive(token);
    }

    @Override
    @Transactional(readOnly = true)
    public String getUserIdByToken(String token) {
        String userId = queuePort.userIdOf(token);
        if (userId == null) {
            throw new RuntimeException("유효하지 않거나 만료된 토큰입니다");
        }
        return userId;
    }

    // 대기열 처리용 메서드 추가
    public void processQueue() {
        RedisQueueAdapter redisAdapter = (RedisQueueAdapter) queuePort;

        // 현재 활성 사용자 수 확인
        Long activeCount = redisAdapter.getActiveCount();

        // 활성화 가능한 슬롯 수 계산
        int availableSlots = 100 - activeCount.intValue();

        if (availableSlots > 0) {
            // 대기열에서 활성화
            redisAdapter.activateNextUsers(availableSlots);
        }
    }
}