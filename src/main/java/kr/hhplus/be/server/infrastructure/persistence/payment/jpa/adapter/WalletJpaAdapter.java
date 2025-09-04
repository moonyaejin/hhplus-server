package kr.hhplus.be.server.infrastructure.persistence.payment.jpa.adapter;

import jakarta.transaction.Transactional;
import kr.hhplus.be.server.domain.reservation.model.exception.InsufficientBalance;
import kr.hhplus.be.server.application.port.out.WalletPort;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWallet;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.WalletLedger;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletRepository;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.WalletLedgerRepository;
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
