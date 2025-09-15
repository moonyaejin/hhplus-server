package kr.hhplus.be.server.domain.reservation;

public class SeatAlreadyConfirmedException extends RuntimeException {
    public SeatAlreadyConfirmedException(String message) {
        super(message);
    }
}