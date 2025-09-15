package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWalletJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.WalletLedgerJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.WalletLedgerJpaRepository;
import kr.hhplus.be.server.web.wallet.dto.ChargeRequest;
import kr.hhplus.be.server.web.wallet.dto.ChargeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class WalletService {  // 레이어드 아키텍처

    private final UserWalletJpaRepository walletRepository;
    private final WalletLedgerJpaRepository ledgerRepository;

    /**
     * 포인트 충전
     */
    public ChargeResponse charge(ChargeRequest request) {
        UUID userId = UUID.fromString(request.userId());

        // 멱등성 체크
        if (request.idempotencyKey() != null &&
                ledgerRepository.existsByUserIdAndIdempotencyKey(userId, request.idempotencyKey())) {
            UserWalletJpaEntity wallet = walletRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("지갑을 찾을 수 없습니다"));
            return new ChargeResponse(request.userId(), wallet.getBalance());
        }

        // 지갑 조회 (행 잠금)
        UserWalletJpaEntity wallet = walletRepository.findForUpdate(userId)
                .orElseGet(() -> {
                    // 지갑이 없으면 새로 생성
                    UserWalletJpaEntity newWallet = new UserWalletJpaEntity(userId, 0L);
                    return walletRepository.save(newWallet);
                });

        // 잔액 증가
        wallet.setBalance(wallet.getBalance() + request.amount());
        walletRepository.save(wallet);

        // 원장 기록
        WalletLedgerJpaEntity ledger = new WalletLedgerJpaEntity(
                userId,
                request.amount(),
                "CHARGE",
                request.idempotencyKey()
        );
        ledgerRepository.save(ledger);

        return new ChargeResponse(request.userId(), wallet.getBalance());
    }

    /**
     * 잔액 조회
     */
    @Transactional(readOnly = true)
    public long getBalance(String userId) {
        UUID uid = UUID.fromString(userId);
        return walletRepository.findById(uid)
                .map(UserWalletJpaEntity::getBalance)
                .orElse(0L);
    }
}