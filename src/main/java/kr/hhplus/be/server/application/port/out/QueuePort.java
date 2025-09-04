package kr.hhplus.be.server.application.port.out;

import kr.hhplus.be.server.domain.queue.model.QueueToken;

public interface QueuePort {
    boolean isActive(String token);
    String userIdOf(String token);
    void expire(String token);
    QueueToken issue(String userId);
}

