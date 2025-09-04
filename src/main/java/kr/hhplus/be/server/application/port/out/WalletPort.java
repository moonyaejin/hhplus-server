package kr.hhplus.be.server.application.port.out;

public interface WalletPort {
    // 결제
    long pay(String userId, long amount, String idempotencyKey);

    // 충전
    long topUp(String userId, long amount, String idempotencyKey);

    // 현재 잔액 조회
    long balanceOf(String userId);
}

