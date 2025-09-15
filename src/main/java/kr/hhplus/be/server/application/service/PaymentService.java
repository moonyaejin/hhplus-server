package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.PaymentUseCase;
import kr.hhplus.be.server.application.port.out.WalletPort;  // 인터페이스 사용
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.payment.Wallet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 애플리케이션 서비스
 * - 도메인 로직 조율
 * - 트랜잭션 경계 관리
 * - 멱등성 보장
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentService implements PaymentUseCase {

    private final WalletPort walletPort;  // 인터페이스에 의존 (DIP 원칙)

    @Override
    public BalanceResult charge(ChargeCommand command) {
        UserId userId = UserId.ofString(command.userId());

        // 1. 멱등성 체크
        if (walletPort.isIdempotencyKeyUsed(userId, command.idempotencyKey())) {
            long currentBalance = walletPort.balanceOf(command.userId());
            return new BalanceResult(currentBalance);
        }

        // 2. 지갑 조회
        Wallet wallet = walletPort.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("지갑을 찾을 수 없습니다: " + command.userId()));

        // 3. 도메인 로직 실행
        wallet.charge(command.amount(), command.idempotencyKey());

        // 4. 변경사항 저장
        walletPort.save(wallet);
        walletPort.saveLedgerEntry(userId, command.amount(), "TOP_UP", command.idempotencyKey());

        return new BalanceResult(wallet.getBalance().amount());
    }

    @Override
    public BalanceResult pay(PaymentCommand command) {
        UserId userId = UserId.ofString(command.userId());

        // 1. 멱등성 체크
        if (walletPort.isIdempotencyKeyUsed(userId, command.idempotencyKey())) {
            long currentBalance = walletPort.balanceOf(command.userId());
            return new BalanceResult(currentBalance);
        }

        // 2. 지갑 조회
        Wallet wallet = walletPort.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("지갑을 찾을 수 없습니다: " + command.userId()));

        // 3. 도메인 로직 실행
        wallet.pay(command.amount(), command.idempotencyKey());

        // 4. 변경사항 저장
        walletPort.save(wallet);
        walletPort.saveLedgerEntry(userId, -command.amount(), "PAYMENT", command.idempotencyKey());

        return new BalanceResult(wallet.getBalance().amount());
    }

    @Override
    @Transactional(readOnly = true)
    public BalanceResult getBalance(BalanceQuery query) {
        long balance = walletPort.balanceOf(query.userId());
        return new BalanceResult(balance);
    }
}