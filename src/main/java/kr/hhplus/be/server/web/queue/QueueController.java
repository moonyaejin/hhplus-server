package kr.hhplus.be.server.web.queue;

import kr.hhplus.be.server.application.port.in.QueueUseCase;
import kr.hhplus.be.server.application.port.out.QueuePort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueUseCase queueUseCase;
    private final QueuePort queuePort;

    @PostMapping("/token")
    public ResponseEntity<QueueUseCase.TokenInfo> issueToken(@RequestParam String userId) {
        var command = new QueueUseCase.IssueTokenCommand(userId);
        var result = queueUseCase.issueToken(command);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/token/{token}")
    public ResponseEntity<QueueUseCase.TokenInfo> getTokenInfo(@PathVariable String token) {
        var result = queueUseCase.getTokenInfo(token);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    public ResponseEntity<QueueStatusResponse> getQueueStatus() {
        // QueuePort 인터페이스를 직접 사용 (MySQL이든 Redis든 상관없이)
        Long activeCount = queuePort.getActiveCount();
        Long waitingCount = queuePort.getWaitingCount();

        QueueStatusResponse response = new QueueStatusResponse(
                activeCount,
                100 - activeCount,  // 사용 가능한 슬롯
                waitingCount,
                waitingCount > 0 ? (waitingCount * 10 / 60) : 0L  // 예상 대기 시간(분)
        );

        return ResponseEntity.ok(response);
    }

    // DTO를 내부 record로 정의
    public record QueueStatusResponse(
            Long activeUsers,
            Long availableSlots,
            Long waitingUsers,
            Long estimatedWaitMinutes
    ) {}
}