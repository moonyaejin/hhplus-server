package kr.hhplus.be.server.domain.reservation;

public class QueueTokenExpiredException extends RuntimeException {
    public QueueTokenExpiredException(String message) {
        super(message);
    }
}