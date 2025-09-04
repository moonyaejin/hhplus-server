package kr.hhplus.be.server.application.usecase.balance;

import kr.hhplus.be.server.application.port.out.WalletPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class BalanceService implements BalanceUseCase {

    private final WalletPort wallet;

    @Override
    public BalanceResult balanceOf(String userId) {
        return new BalanceResult(wallet.balanceOf(userId));
    }

    @Override
    public BalanceResult topUp(String userId, long amount, String idempotencyKey) {
        return new BalanceResult(wallet.topUp(userId, amount, idempotencyKey));
    }

    @Override
    public BalanceResult pay(String userId, long amount, String idempotencyKey) {
        return new BalanceResult(wallet.pay(userId, amount, idempotencyKey));
    }
}
