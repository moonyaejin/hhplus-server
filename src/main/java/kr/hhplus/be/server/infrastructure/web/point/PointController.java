package kr.hhplus.be.server.infrastructure.web.point;

import kr.hhplus.be.server.application.dto.BalanceResponse;
import kr.hhplus.be.server.application.usecase.balance.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/points")
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    @PostMapping("/charge")
    public BalanceResponse charge(@RequestParam String userId,
                                  @RequestParam long amount,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        long balance = pointService.charge(userId, amount, idempotencyKey);
        return new BalanceResponse(balance);
    }

    @GetMapping("/{userId}")
    public BalanceResponse getBalance(@PathVariable String userId) {
        long balance = pointService.getBalance(userId);
        return new BalanceResponse(balance);
    }

}
