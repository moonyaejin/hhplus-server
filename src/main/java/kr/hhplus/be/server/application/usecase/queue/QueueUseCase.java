package kr.hhplus.be.server.application.usecase.queue;

public interface QueueUseCase {
    QueueResult issue(String userId);

    boolean isActive(String token);

    void expire(String token);

    record QueueResult(String token, String userId) {
    }
}
