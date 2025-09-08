package kr.hhplus.be.server.application.usecase.queue;

import kr.hhplus.be.server.application.port.in.QueueUseCase;
import kr.hhplus.be.server.application.port.out.QueuePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class QueueService implements QueueUseCase {

    private final QueuePort queue;

    @Override
    public QueueResult issue(String userId) {
        var token = queue.issue(userId);                // 어댑터가 발급
        return new QueueResult(token.value(), userId);  // 문자열로 응답에 담기
    }

    @Override
    public boolean isActive(String token) {
        return queue.isActive(token);
    }

    @Override
    public void expire(String token) {
        queue.expire(token);
    }
}
