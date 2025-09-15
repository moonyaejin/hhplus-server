package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.QueueUseCase;
import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.domain.queue.QueueTokenNotActiveException;
import kr.hhplus.be.server.domain.queue.model.QueueToken;
import kr.hhplus.be.server.domain.reservation.QueueTokenExpiredException;
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
        boolean isActive = queuePort.isActive(token.value());

        Long waitingNumber = 0L;
        String status = "ACTIVE";

        if (!isActive) {
            waitingNumber = queuePort.getWaitingPosition(token.value());
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
            Long position = queuePort.getWaitingPosition(token);

            if (position == null) {
                throw new QueueTokenExpiredException("유효하지 않거나 만료된 토큰입니다");
            }

            String userId = queuePort.userIdOf(token);
            return new TokenInfo(
                    token,
                    userId,
                    "WAITING",
                    position,
                    null
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
            throw new QueueTokenExpiredException("유효하지 않거나 만료된 토큰입니다");
        }
        return userId;
    }

    public void processQueue() {
        Long activeCount = queuePort.getActiveCount();
        int availableSlots = 100 - activeCount.intValue();

        if (availableSlots > 0) {
            queuePort.activateNextUsers(availableSlots);
        }
    }

    public void validateActiveToken(String token) {
        if (!queuePort.isActive(token)) {
            Long position = queuePort.getWaitingPosition(token);
            if (position != null) {
                throw new QueueTokenNotActiveException(
                        String.format("대기 중인 토큰입니다. 현재 순번: %d", position)
                );
            }
            throw new QueueTokenExpiredException("유효하지 않거나 만료된 토큰입니다");
        }
    }
}