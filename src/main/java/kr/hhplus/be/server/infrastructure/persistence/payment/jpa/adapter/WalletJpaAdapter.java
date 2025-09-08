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

        // 멱등성 체크
        if (idempotencyKey != null && ledgerRepo.existsByUserIdAndIdempotencyKey(uid, idempotencyKey)) {
            return balanceOf(userId);
        }

        UserWallet wallet = walletRepo.findById(uid)
                .orElseThrow(() -> new IllegalStateException("wallet not found"));

        if (wallet.getBalance() < amount) {
            throw new IllegalArgumentException("insufficient balance");
        }

        wallet.decrease(amount);

        ledgerRepo.save(new WalletLedger(uid, -amount, "PAYMENT", idempotencyKey));
        return wallet.getBalance();
    }

    @Transactional
    @Override
    public long topUp(String userId, long amount, String idempotencyKey) {
        UUID uid = UUID.fromString(userId);

        // 멱등성 체크
        if (idempotencyKey != null && ledgerRepo.existsByUserIdAndIdempotencyKey(uid, idempotencyKey)) {
            return balanceOf(userId);
        }

        UserWallet wallet = walletRepo.findById(uid)
                .orElseThrow(() -> new IllegalStateException("wallet not found"));

        wallet.increase(amount);

        ledgerRepo.save(new WalletLedger(uid, amount, "TOP_UP", idempotencyKey));
        return wallet.getBalance();
    }

    @Override
    public long balanceOf(String userId) {
        UUID uid = UUID.fromString(userId);
        return walletRepo.findById(uid)
                .map(UserWallet::getBalance)
                .orElseThrow(() -> new IllegalStateException("wallet not found"));
    }
}