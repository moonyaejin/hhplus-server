package kr.hhplus.be.server.domain.queue;

import kr.hhplus.be.server.domain.common.UserId;
import java.time.LocalDateTime;

public class QueueEntry {
    private final String token;
    private final UserId userId;
    private final LocalDateTime enteredAt;
    private final Long position;
    private QueueStatus status;

    public QueueEntry(String token, UserId userId, Long position) {
        this.token = token;
        this.userId = userId;
        this.position = position;
        this.enteredAt = LocalDateTime.now();
        this.status = QueueStatus.WAITING;
    }

    public void activate() {
        if (this.status != QueueStatus.WAITING) {
            throw new IllegalStateException("대기 중인 상태에서만 활성화 가능합니다");
        }
        this.status = QueueStatus.ACTIVE;
    }

    public void expire() {
        this.status = QueueStatus.EXPIRED;
    }

    // Getters
    public String getToken() { return token; }
    public UserId getUserId() { return userId; }
    public Long getPosition() { return position; }
    public QueueStatus getStatus() { return status; }
    public LocalDateTime getEnteredAt() { return enteredAt; }
}