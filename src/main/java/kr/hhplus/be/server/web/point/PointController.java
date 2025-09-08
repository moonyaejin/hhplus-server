package kr.hhplus.be.server.web.point;

import kr.hhplus.be.server.application.dto.payment.BalanceResponse;
import kr.hhplus.be.server.application.dto.payment.MoneyRequest;
import kr.hhplus.be.server.application.port.in.PointUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
@Validated
public class PointController {

    private static final String HDR_IDEMPOTENCY = "Idempotency-Key";

    private final PointUseCase pointUseCase;

    @PostMapping("/charge")
    public ResponseEntity<BalanceResponse> charge(
            @RequestParam String userId,
            @RequestHeader(HDR_IDEMPOTENCY) String idempotencyKey,
            @RequestBody @Validated MoneyRequest body
    ) {
        long balance = pointUseCase.charge(userId, body.amount(), idempotencyKey);
        return ResponseEntity.ok(new BalanceResponse(balance));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable String userId) {
        long balance = pointUseCase.getBalance(userId);
        return ResponseEntity.ok(new BalanceResponse(balance));
    }
}
