package kr.hhplus.be.server.web.queue;

import kr.hhplus.be.server.application.port.in.QueueUseCase;
import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.infrastructure.persistence.queue.redis.RedisQueueAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueUseCase queueUseCase;
    private final QueuePort queuePort;  // QueuePort 주입 추가

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
        // QueuePort를 RedisQueueAdapter로 캐스팅
        if (!(queuePort instanceof RedisQueueAdapter)) {
            // 기본 응답 반환
            return ResponseEntity.ok(new QueueStatusResponse(0L, 100L, 0L, 0L));
        }

        RedisQueueAdapter adapter = (RedisQueueAdapter) queuePort;

        Long activeCount = adapter.getActiveCount();
        Long waitingCount = adapter.getWaitingCount();

        QueueStatusResponse response = new QueueStatusResponse(
                activeCount,
                100 - activeCount,  // 사용 가능한 슬롯
                waitingCount,
                waitingCount > 0 ? waitingCount * 10 / 60 : 0  // 예상 대기 시간(분)
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