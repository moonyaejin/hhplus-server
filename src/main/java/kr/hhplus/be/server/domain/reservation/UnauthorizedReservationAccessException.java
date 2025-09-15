package kr.hhplus.be.server.domain.reservation;

public class UnauthorizedReservationAccessException extends RuntimeException {
    public UnauthorizedReservationAccessException(String message) {
        super(message);
    }
}