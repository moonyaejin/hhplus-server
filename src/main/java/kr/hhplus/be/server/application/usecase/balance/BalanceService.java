package kr.hhplus.be.server.application.usecase.balance;

import kr.hhplus.be.server.application.port.in.BalanceUseCase;
import kr.hhplus.be.server.application.port.out.WalletPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BalanceService implements BalanceUseCase {
    private final WalletPort walletPort;

    @Override
    @Transactional(readOnly = true)
    public BalanceResult balanceOf(String userId) {
        return new BalanceResult(walletPort.balanceOf(userId));
    }

    @Override
    @Transactional
    public BalanceResult topUp(String userId, long amount, String idempotencyKey) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be greater than zero");
        return new BalanceResult(walletPort.topUp(userId, amount, idempotencyKey));
    }

    @Override
    @Transactional
    public BalanceResult pay(String userId, long amount, String idempotencyKey) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be greater than zero");
        return new BalanceResult(walletPort.pay(userId, amount, idempotencyKey));
    }
}
