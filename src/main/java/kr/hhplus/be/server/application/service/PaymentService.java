package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.PaymentUseCase;
import kr.hhplus.be.server.application.port.out.WalletPort;  // 인터페이스 사용
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.payment.Wallet;
import kr.hhplus.be.server.infrastructure.redis.lock.RedisDistributedLock;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 애플리케이션 서비스
 * - 도메인 로직 조율
 * - 트랜잭션 경계 관리
 * - 멱등성 보장
 *
 * [분산락 적용 포인트]
 * 1. charge: 사용자별 락 (동시 충전 방지)
 * 2. pay: 사용자별 락 (동시 결제 방지)
 *
 * [실행 순서]
 * - 락 획득 → 멱등성 체크 → 트랜잭션 시작 → 작업 → 트랜잭션 커밋 → 락 해제
 * - 멱등성 체크를 락 안에서 수행하여 동시 요청 시 일관성 보장
 */
@Service
@RequiredArgsConstructor
public class PaymentService implements PaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final WalletPort walletPort;  // 인터페이스에 의존 (DIP 원칙)
    private final RedisDistributedLock distributedLock;
    private final TransactionTemplate transactionTemplate;

    /**
     * 포인트 충전
     * - 분산락: lock:charge:user:{userId}
     * - 범위: 사용자별 충전 직렬화
     * - 순서: 락 획득 → 멱등성 체크 → 트랜잭션 실행
     */
    @Override
    public BalanceResult charge(ChargeCommand command) {
        UserId userId = UserId.ofString(command.userId());
        String lockKey = "lock:charge:user:" + command.userId();

        // 락 먼저 획득
        return distributedLock.executeWithLock(
                lockKey,
                10L,        // TTL: 10초
                3,          // 최대 3번 재시도
                100L,       // 100ms 대기 후 재시도
                () -> {
                    // 락 안에서 멱등성 체크 (동시 요청 시 일관성 보장)
                    if (walletPort.isIdempotencyKeyUsed(userId, command.idempotencyKey())) {
                        long currentBalance = walletPort.balanceOf(command.userId());
                        return new BalanceResult(currentBalance);
                    }

                    // 트랜잭션 실행
                    return transactionTemplate.execute(status ->
                            executeCharge(command, userId)
                    );
                }
        );
    }

    /**
     * 포인트 충전 실제 로직 (분산락 + 트랜잭션 내부)
     */
    private BalanceResult executeCharge(ChargeCommand command, UserId userId) {
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

    /**
     * 결제 처리
     * - 분산락: lock:payment:user:{userId}
     * - 범위: 사용자별 결제 직렬화
     * - 순서: 락 획득 → 멱등성 체크 → 트랜잭션 실행
     */
    @Override
    public BalanceResult pay(PaymentCommand command) {
        UserId userId = UserId.ofString(command.userId());
        String lockKey = "lock:payment:user:" + command.userId();

        // 락 먼저 획득
        return distributedLock.executeWithLock(
                lockKey,
                10L,        // TTL: 10초
                3,          // 최대 3번 재시도
                100L,       // 100ms 대기 후 재시도
                () -> {
                    // 락 안에서 멱등성 체크 (동시 요청 시 일관성 보장)
                    if (walletPort.isIdempotencyKeyUsed(userId, command.idempotencyKey())) {
                        long currentBalance = walletPort.balanceOf(command.userId());
                        return new BalanceResult(currentBalance);
                    }

                    // 트랜잭션 실행
                    return transactionTemplate.execute(status ->
                            executePay(command, userId)
                    );
                }
        );
    }

    /**
     * 결제 처리 실제 로직 (분산락 + 트랜잭션 내부)
     */
    private BalanceResult executePay(PaymentCommand command, UserId userId) {
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
    public BalanceResult getBalance(BalanceQuery query) {
        long balance = walletPort.balanceOf(query.userId());
        return new BalanceResult(balance);
    }
}