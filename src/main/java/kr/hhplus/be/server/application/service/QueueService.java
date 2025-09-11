package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.QueueUseCase;
import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.domain.queue.model.QueueToken;
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

        return new TokenInfo(
                token.value(),
                command.userId(),
                "ACTIVE", // Redis 기반이므로 즉시 활성화
                0L, // 대기 번호 (즉시 활성화되므로 0)
                LocalDateTime.now().plusMinutes(TOKEN_TTL_MINUTES)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public TokenInfo getTokenInfo(String token) {
        if (!queuePort.isActive(token)) {
            throw new RuntimeException("유효하지 않거나 만료된 토큰입니다");
        }

        String userId = queuePort.userIdOf(token);

        return new TokenInfo(
                token,
                userId,
                "ACTIVE",
                0L, // 활성 상태이므로 대기 번호 0
                LocalDateTime.now().plusMinutes(TOKEN_TTL_MINUTES) // 대략적인 만료 시간
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
        if (!queuePort.isActive(token)) {
            throw new RuntimeException("유효하지 않거나 만료된 토큰입니다");
        }
        return queuePort.userIdOf(token);
    }
}