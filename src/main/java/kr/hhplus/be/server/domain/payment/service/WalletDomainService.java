package kr.hhplus.be.server.domain.payment.service;

import kr.hhplus.be.server.application.usecase.payment.Wallet;

import java.util.Objects;

/**
 * Wallet 관련 도메인 규칙을 묶어두는 서비스.
 * - 멱등성 체크/원장 기록/영속화는 인프라/애플리케이션에서 처리
 * - 여기서는 "잔액 갱신 규칙"만 책임진다.
 */

public final class WalletDomainService {

    public long topUp(Wallet wallet, long amount) {
        Objects.requireNonNull(wallet, "wallet");
        wallet.charge(amount);
        return wallet.balance();
    }

    public long pay(Wallet wallet, long amount) {
        Objects.requireNonNull(wallet, "wallet");
        wallet.pay(amount);
        return wallet.balance();
    }

    // refund, reserve, cancel 등 확장
}