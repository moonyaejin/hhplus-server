package kr.hhplus.be.server.application.port.out;

import kr.hhplus.be.server.domain.queue.model.QueueToken;

public interface QueuePort {

    // String 기반 메서드만 포트 인터페이스에 정의
    boolean isActive(String token);
    String userIdOf(String token);
    void expire(String token);
    QueueToken issue(String userId);  // 발급은 QueueToken 반환
}