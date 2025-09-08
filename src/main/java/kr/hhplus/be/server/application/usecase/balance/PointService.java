package kr.hhplus.be.server.application.usecase.balance;

import kr.hhplus.be.server.application.port.in.PointUseCase;
import kr.hhplus.be.server.application.port.out.WalletPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PointService implements PointUseCase {
    private final WalletPort walletPort;

    @Override
    @Transactional
    public long charge(String userId, long amount, String idempotencyKey) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        return walletPort.topUp(userId, amount, idempotencyKey);
    }

    @Override
    @Transactional(readOnly = true)
    public long getBalance(String userId) {
        return walletPort.balanceOf(userId);
    }
}
