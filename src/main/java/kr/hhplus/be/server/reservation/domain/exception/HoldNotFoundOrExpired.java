package kr.hhplus.be.server.reservation.domain.exception;

public class HoldNotFoundOrExpired extends RuntimeException { public HoldNotFoundOrExpired(){ super("hold not found or expired"); } }
