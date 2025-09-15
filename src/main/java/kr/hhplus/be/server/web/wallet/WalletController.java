package kr.hhplus.be.server.web.wallet;

import kr.hhplus.be.server.application.service.WalletService;
import kr.hhplus.be.server.web.wallet.dto.ChargeRequest;
import kr.hhplus.be.server.web.wallet.dto.ChargeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;  // Service 직접 주입

    @PostMapping("/charge")
    public ResponseEntity<ChargeResponse> charge(
            @RequestBody ChargeRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        ChargeRequest requestWithKey = new ChargeRequest(
                request.userId(),
                request.amount(),
                idempotencyKey != null ? idempotencyKey : request.idempotencyKey()
        );

        ChargeResponse response = walletService.charge(requestWithKey);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{userId}/balance")
    public ResponseEntity<Long> getBalance(@PathVariable String userId) {
        long balance = walletService.getBalance(userId);
        return ResponseEntity.ok(balance);
    }
}