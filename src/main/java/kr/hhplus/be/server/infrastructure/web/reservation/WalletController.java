package kr.hhplus.be.server.infrastructure.web.reservation;

import kr.hhplus.be.server.application.port.out.WalletPort;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletPort wallet;

    // 잔액조회
    @GetMapping("/{userId}")
    public BalanceResponse balance(@PathVariable String userId) {
        return new BalanceResponse(wallet.balanceOf(userId));
    }

    // 충전
    @PostMapping("/{userId}/top-up")
    public BalanceResponse topUp(@PathVariable String userId,
                                 @RequestParam long amount,
                                 @RequestParam String idempotencyKey) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        long newBalance = wallet.topUp(userId, amount, idempotencyKey);
        return new BalanceResponse(newBalance);
    }

    // 결제
    @PostMapping("/{userId}/pay")
    public BalanceResponse pay(@PathVariable String userId,
                               @RequestParam long amount,
                               @RequestParam String idempotencyKey) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        long newBalance = wallet.pay(userId, amount, idempotencyKey);
        return new BalanceResponse(newBalance);
    }

    public record BalanceResponse(long balance) {}
}
