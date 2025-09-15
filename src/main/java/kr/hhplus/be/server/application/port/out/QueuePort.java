package kr.hhplus.be.server.application.port.out;

import kr.hhplus.be.server.domain.queue.model.QueueToken;

public interface QueuePort {
    boolean isActive(String token);
    String userIdOf(String token);
    void expire(String token);
    QueueToken issue(String userId);

    Long getWaitingPosition(String token);  // 대기 순번 조회
    Long getActiveCount();  // 활성 사용자 수
    Long getWaitingCount();  // 대기 사용자 수
    void activateNextUsers(int count);  // 대기열 활성화
}