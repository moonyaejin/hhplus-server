package kr.hhplus.be.server.application.usecase.balance;

import kr.hhplus.be.server.application.port.out.WalletPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PointService {
    private final WalletPort walletPort;

    public long charge(String userId, long amount, String idempotencyKey) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        return walletPort.topUp(userId, amount, idempotencyKey);
    }

    public long getBalance(String userId) {
        return walletPort.balanceOf(userId);
    }
}
