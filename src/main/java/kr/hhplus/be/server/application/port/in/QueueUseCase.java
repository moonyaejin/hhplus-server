package kr.hhplus.be.server.application.port.in;

public interface QueueUseCase {
    QueueResult issue(String userId);

    boolean isActive(String token);

    void expire(String token);

    record QueueResult(String token, String userId) {
    }
}
