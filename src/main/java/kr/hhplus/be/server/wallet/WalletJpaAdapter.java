package kr.hhplus.be.server.wallet;

import jakarta.transaction.Transactional;
import kr.hhplus.be.server.reservation.domain.exception.InsufficientBalance;
import kr.hhplus.be.server.reservation.port.out.WalletPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WalletJpaAdapter implements WalletPort {

    private final UserWalletRepository walletRepo;
    private final WalletLedgerRepository ledgerRepo;

    @Transactional
    @Override
    public long pay(String userId, long amount, String idempotencyKey) {
        UUID uid = UUID.fromString(userId);

        // 멱등성 체크 (이미 처리된 요청이면 현재 잔액만 반환)
        if (idempotencyKey != null && ledgerRepo.existsByUserIdAndIdempotencyKey(uid, idempotencyKey)) {
            return walletRepo.findById(uid)
                    .map(UserWallet::getBalance)
                    .orElseThrow(() -> new IllegalStateException("wallet not fount"));
        }

        UserWallet wallet = walletRepo.findById(uid)
                .orElseThrow(() -> new IllegalStateException("wallet not found"));

        if (wallet.getBalance() < amount) {
            throw new InsufficientBalance();
        }

        wallet.decrease(amount);

        ledgerRepo.save(new WalletLedger(uid, -amount, "PAYMENT", idempotencyKey));
        return wallet.getBalance();
    }
}
