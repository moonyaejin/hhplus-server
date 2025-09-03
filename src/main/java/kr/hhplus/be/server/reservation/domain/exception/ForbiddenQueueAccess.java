package kr.hhplus.be.server.reservation.domain.exception;

public class ForbiddenQueueAccess extends RuntimeException{ public ForbiddenQueueAccess() { super("queue not active"); }}
