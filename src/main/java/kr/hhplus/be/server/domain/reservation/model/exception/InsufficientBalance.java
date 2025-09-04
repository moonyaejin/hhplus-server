package kr.hhplus.be.server.domain.reservation.model.exception;

// 지갑 잔액 부족
public class InsufficientBalance extends RuntimeException {
    public InsufficientBalance() { super("insufficient balance"); } }
