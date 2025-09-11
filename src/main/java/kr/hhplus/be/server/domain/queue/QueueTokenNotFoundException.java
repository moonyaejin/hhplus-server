package kr.hhplus.be.server.domain.queue;

public class QueueTokenNotFoundException extends RuntimeException {
    public QueueTokenNotFoundException(String message) {
        super(message);
    }
}