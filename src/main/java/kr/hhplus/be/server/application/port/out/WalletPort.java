package kr.hhplus.be.server.application.port.out;

import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.payment.Wallet;
import java.util.Optional;

/**
 * 지갑 관련 외부 포트 인터페이스
 * - 순수 데이터 접근만 담당
 * - 비즈니스 로직은 PaymentService에서 처리
 */
public interface WalletPort {

    // 조회
    Optional<Wallet> findByUserId(UserId userId);
    long balanceOf(String userId);

    // 저장
    void save(Wallet wallet);

    // 멱등성 체크
    boolean isIdempotencyKeyUsed(UserId userId, String idempotencyKey);

    // 원장 기록
    void saveLedgerEntry(UserId userId, long amount, String reason, String idempotencyKey);
}