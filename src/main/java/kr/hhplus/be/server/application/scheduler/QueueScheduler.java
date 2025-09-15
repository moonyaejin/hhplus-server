package kr.hhplus.be.server.application.scheduler;

import kr.hhplus.be.server.application.service.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.queue.scheduler.enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class QueueScheduler {

    private final QueueService queueService;

    @Scheduled(fixedDelay = 1000)
    public void processWaitingQueue() {
        try {
            queueService.processQueue();
        } catch (Exception e) {
            log.error("대기열 처리 중 오류 발생", e);
        }
    }
}