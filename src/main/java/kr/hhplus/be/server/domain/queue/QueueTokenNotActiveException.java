package kr.hhplus.be.server.domain.queue;

public class QueueTokenNotActiveException extends RuntimeException {
    public QueueTokenNotActiveException(String message) {
        super(message);
    }
}