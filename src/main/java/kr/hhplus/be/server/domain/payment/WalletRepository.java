package kr.hhplus.be.server.domain.payment;

import kr.hhplus.be.server.domain.common.UserId;

import java.util.Optional;

public interface WalletRepository {
    void save(Wallet wallet);
    Optional<Wallet> findByUserId(UserId userId);
    Optional<Wallet> findById(WalletId id);
}