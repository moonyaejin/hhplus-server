package kr.hhplus.be.server.infrastructure.persistence.payment.jpa.adapter;

import jakarta.transaction.Transactional;
import kr.hhplus.be.server.application.port.out.WalletPort;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWalletJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.WalletLedgerJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.WalletLedgerJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Primary
@RequiredArgsConstructor
public class WalletJpaAdapter implements WalletPort {

    private final UserWalletJpaRepository walletRepo;
    private final WalletLedgerJpaRepository ledgerRepo;

    @Transactional
    @Override
    public long pay(String userId, long amount, String idempotencyKey) {
        UUID uid = UUID.fromString(userId);

        // 멱등성 체크
        if (idempotencyKey != null &&
                ledgerRepo.existsByUserIdAndIdempotencyKey(uid, idempotencyKey)) {
            return balanceOf(userId);
        }

        UserWalletJpaEntity entity = walletRepo.findById(uid)
                .orElseThrow(() -> new IllegalStateException("wallet not found"));

        // 도메인 객체로 변환
        var wallet = new kr.hhplus.be.server.application.usecase.payment.Wallet(entity.getUserId(), entity.getBalance());
        wallet.pay(amount);

        // 변경 반영
        entity.setBalance(wallet.balance());

        ledgerRepo.save(new WalletLedgerJpaEntity(uid, -amount, "PAYMENT", idempotencyKey));
        return entity.getBalance();
    }

    @Transactional
    @Override
    public long topUp(String userId, long amount, String idempotencyKey) {
        UUID uid = UUID.fromString(userId);

        // 멱등성 체크
        if (idempotencyKey != null &&
                ledgerRepo.existsByUserIdAndIdempotencyKey(uid, idempotencyKey)) {
            return balanceOf(userId);
        }

        UserWalletJpaEntity entity = walletRepo.findById(uid)
                .orElseThrow(() -> new IllegalStateException("wallet not found"));

        var wallet = new kr.hhplus.be.server.application.usecase.payment.Wallet(entity.getUserId(), entity.getBalance());
        wallet.charge(amount);

        entity.setBalance(wallet.balance());

        ledgerRepo.save(new WalletLedgerJpaEntity(uid, amount, "TOP_UP", idempotencyKey));
        return entity.getBalance();
    }

    @Override
    public long balanceOf(String userId) {
        UUID uid = UUID.fromString(userId);
        return walletRepo.findById(uid)
                .map(UserWalletJpaEntity::getBalance)
                .orElseThrow(() -> new IllegalStateException("wallet not found"));
    }
}
