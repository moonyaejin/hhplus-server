package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.PaymentUseCase;
import kr.hhplus.be.server.application.port.out.WalletPort;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.payment.InsufficientBalanceException;
import kr.hhplus.be.server.domain.payment.Wallet;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentService implements PaymentUseCase {

    private final WalletPort walletPort;
    private final UserWalletJpaRepository walletRepository;

    // 조건부 UPDATE 사용 여부를 설정으로 관리
    @Value("${app.payment.use-conditional-update:false}")
    private boolean useConditionalUpdate;

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

        // 2. 설정에 따라 동시성 제어 방식 선택
        if (useConditionalUpdate) {
            log.debug("조건부 UPDATE 방식으로 결제 처리: userId={}", command.userId());
            return payWithConditionalUpdate(command);
        } else {
            log.debug("도메인 로직 방식으로 결제 처리: userId={}", command.userId());
            return payWithDomainLogic(command);
        }
    }

    /**
     * 기존 방식: 도메인 객체를 통한 결제 처리
     * - SELECT + 도메인 로직 + UPDATE
     * - 비관적 락(findForUpdate) 또는 낙관적 락(@Version) 사용
     */
    private BalanceResult payWithDomainLogic(PaymentCommand command) {
        UserId userId = UserId.ofString(command.userId());

        // 비관적 락으로 지갑 조회
        Wallet wallet = walletPort.findByUserIdWithLock(userId)  // ← 변경
                .orElseThrow(() -> new IllegalStateException("지갑을 찾을 수 없습니다: " + command.userId()));

        // 도메인 로직 실행 (잔액 검증 포함)
        wallet.pay(command.amount(), command.idempotencyKey());

        // 변경사항 저장
        walletPort.save(wallet);
        walletPort.saveLedgerEntry(userId, -command.amount(), "PAYMENT", command.idempotencyKey());

        return new BalanceResult(wallet.getBalance().amount());
    }

    /**
     * 개선 방식: 조건부 UPDATE를 통한 결제 처리
     * - 단일 쿼리로 원자적 차감 (WHERE balance >= amount)
     * - DB 레벨에서 동시성 제어
     * - 음수 잔액 원천 차단
     */
    private BalanceResult payWithConditionalUpdate(PaymentCommand command) {
        UserId userId = UserId.ofString(command.userId());

        // 조건부 UPDATE로 차감 (원자적 연산)
        int updatedRows = walletRepository.decreaseBalance(
                userId.asUUID(),
                command.amount()
        );

        // UPDATE 실패 = 잔액 부족
        if (updatedRows == 0) {
            long currentBalance = walletPort.balanceOf(command.userId());
            throw InsufficientBalanceException.of(command.amount(), currentBalance);
        }

        // 원장 기록
        walletPort.saveLedgerEntry(userId, -command.amount(), "PAYMENT", command.idempotencyKey());

        // 현재 잔액 조회 후 반환
        long currentBalance = walletPort.balanceOf(command.userId());

        log.info("결제 성공(조건부UPDATE): userId={}, amount={}, remainingBalance={}",
                command.userId(), command.amount(), currentBalance);

        return new BalanceResult(currentBalance);
    }

    @Override
    @Transactional(readOnly = true)
    public BalanceResult getBalance(BalanceQuery query) {
        long balance = walletPort.balanceOf(query.userId());
        return new BalanceResult(balance);
    }
}