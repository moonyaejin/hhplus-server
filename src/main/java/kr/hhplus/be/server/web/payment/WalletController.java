package kr.hhplus.be.server.web.payment;

import kr.hhplus.be.server.application.dto.payment.BalanceResponse;
import kr.hhplus.be.server.application.dto.payment.MoneyRequest;
import kr.hhplus.be.server.application.port.in.BalanceUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
@Validated
public class WalletController {

    private static final String HDR_IDEMPOTENCY = "Idempotency-Key";

    private final BalanceUseCase balanceUseCase;

    // 잔액 조회
    @GetMapping("/{userId}")
    public ResponseEntity<BalanceResponse> balance(@PathVariable String userId) {
        var result = balanceUseCase.balanceOf(userId);
        return ResponseEntity.ok(new BalanceResponse(result.balance()));
    }

    // 충전
    @PostMapping("/{userId}/top-up")
    public ResponseEntity<BalanceResponse> topUp(@PathVariable String userId,
                                                 @RequestHeader(HDR_IDEMPOTENCY) String idempotencyKey,
                                                 @RequestBody @Validated MoneyRequest req) {
        var result = balanceUseCase.topUp(userId, req.amount(), idempotencyKey);
        return ResponseEntity.ok(new BalanceResponse(result.balance()));
    }

    // 결제
    @PostMapping("/{userId}/pay")
    public ResponseEntity<BalanceResponse> pay(@PathVariable String userId,
                                               @RequestHeader(HDR_IDEMPOTENCY) String idempotencyKey,
                                               @RequestBody @Validated MoneyRequest req) {
        var result = balanceUseCase.pay(userId, req.amount(), idempotencyKey);
        return ResponseEntity.ok(new BalanceResponse(result.balance()));
    }
}
