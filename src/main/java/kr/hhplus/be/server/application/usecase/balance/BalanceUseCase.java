package kr.hhplus.be.server.application.usecase.balance;

public interface BalanceUseCase {
    BalanceResult balanceOf(String userId);
    BalanceResult topUp(String userId, long amount, String idempotencyKey);
    BalanceResult pay(String userId, long amount, String idempotencyKey);

    record BalanceResult(long balance) {}
}
