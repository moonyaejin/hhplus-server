package kr.hhplus.be.server.application.port.in;

import java.time.LocalDateTime;

public interface QueueUseCase {

    record IssueTokenCommand(String userId) {}

    record TokenInfo(
            String token,
            String userId,
            String status,
            long waitingNumber,
            LocalDateTime expiresAt
    ) {}

    TokenInfo issueToken(IssueTokenCommand command);
    TokenInfo getTokenInfo(String token);
    void expireToken(String token);
    boolean isTokenActive(String token);
    String getUserIdByToken(String token);
}