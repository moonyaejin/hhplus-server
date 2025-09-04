package kr.hhplus.be.server.domain.reservation.model.exception;

// 유효하지 않은 대기열 접근(토큰 무효/사용자 불일치 등)
public class ForbiddenQueueAccess extends RuntimeException{
    public ForbiddenQueueAccess() { super("queue not active"); }}
