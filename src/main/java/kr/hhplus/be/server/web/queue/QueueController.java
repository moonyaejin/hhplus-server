package kr.hhplus.be.server.web.queue;

import kr.hhplus.be.server.application.port.in.QueueUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueUseCase queueUseCase;

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
}