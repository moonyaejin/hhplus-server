package kr.hhplus.be.server.application.port.in;

public interface PaymentUseCase {

    record ChargeCommand(String userId, long amount, String idempotencyKey) {}
    record PaymentCommand(String userId, long amount, String idempotencyKey) {}
    record RefundCommand(String userId, long amount, String idempotencyKey) {}
    record BalanceQuery(String userId) {}

    record BalanceResult(long balance) {}

    BalanceResult charge(ChargeCommand command);
    BalanceResult pay(PaymentCommand command);
    BalanceResult refund(RefundCommand command);
    BalanceResult getBalance(BalanceQuery query);
}