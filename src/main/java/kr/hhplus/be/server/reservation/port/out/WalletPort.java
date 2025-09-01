package kr.hhplus.be.server.reservation.port.out;

public interface WalletPort {
    long pay(String userId, long amount, String idempotencyKey); // remaining balance
}

