package kr.hhplus.be.server.domain.reservation.model.exception;

// 이미 다른 사용자가 좌석을 홀드 중일 때
public class SeatAlreadyHeld extends RuntimeException {
    public SeatAlreadyHeld() { super("seat already held"); } }

