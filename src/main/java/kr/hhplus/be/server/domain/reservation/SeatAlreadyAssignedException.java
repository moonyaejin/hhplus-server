package kr.hhplus.be.server.domain.reservation;

public class SeatAlreadyAssignedException extends RuntimeException {
    public SeatAlreadyAssignedException(String message) {
        super(message);
    }
}