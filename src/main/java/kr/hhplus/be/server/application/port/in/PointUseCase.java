package kr.hhplus.be.server.application.port.in;

public interface PointUseCase {
    long charge(String userId, long amount, String idempotencyKey);
    long getBalance(String userId);
}