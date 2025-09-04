package kr.hhplus.be.server.domain.reservation.model.exception;

// 해당 사용자가 홀드하지 않았거나, 홀드가 만료된 경우
public class HoldNotFoundOrExpired extends RuntimeException {
    public HoldNotFoundOrExpired() { super("hold not found or expired"); } }
