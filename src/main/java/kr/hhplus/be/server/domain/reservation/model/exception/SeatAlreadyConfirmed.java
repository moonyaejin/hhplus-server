package kr.hhplus.be.server.domain.reservation.model.exception;

// 좌석이 이미 확정(결제 완료)된 경우
public class SeatAlreadyConfirmed extends RuntimeException {
    public SeatAlreadyConfirmed() { super("seat already confirmed"); } }
