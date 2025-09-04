package kr.hhplus.be.server.infrastructure.web.payment;

import jakarta.transaction.Transactional;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.UserWallet;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.UserWalletRepository;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.entity.WalletLedger;
import kr.hhplus.be.server.infrastructure.persistence.payment.jpa.repository.WalletLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController @RequestMapping("/wallets")
@RequiredArgsConstructor
public class WalletController {
    private final UserWalletRepository userWalletRepository;
    private final WalletLedgerRepository walletLedgerRepository;

    @GetMapping("/{userId}")
    public Map<String, Object> balance(@PathVariable UUID userId) {
        long balance = userWalletRepository.findById(userId).map(UserWallet::getBalance).orElse(0L);
        return Map.of("userId", userId, "balance", balance);
    }

    @PostMapping("/{userId}/charge")
    @Transactional
    public Map<String, Object> charge(@PathVariable UUID userId,
                                      @RequestParam long amount,
                                      @RequestHeader(value="Idempotency-Key", required = false) String idem) {
        // 멱등 처리
        if (idem != null && walletLedgerRepository.existsByUserIdAndIdempotencyKey(userId, idem)) {
            long balance = userWalletRepository.findById(userId).map(UserWallet::getBalance).orElseThrow();
            return Map.of("balance", balance);
        }
        UserWallet w = userWalletRepository.findForUpdate(userId).orElseGet(() -> new UserWallet(userId, 0));
        w.increase(amount);
        userWalletRepository.save(w);
        walletLedgerRepository.save(new WalletLedger(userId, amount, "CHARGE", idem));
        return Map.of("balance", w.getBalance());
    }
}
