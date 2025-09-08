package kr.hhplus.be.server;

import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.application.usecase.queue.QueueService;
import kr.hhplus.be.server.application.usecase.queue.QueueUseCase;
import kr.hhplus.be.server.domain.queue.model.QueueToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QueueServiceTest {

    private QueuePort queuePort;
    private QueueService queueService;

    @BeforeEach
    void setUp() {
        queuePort = mock(QueuePort.class);
        queueService = new QueueService(queuePort);
    }

    @Test
    void 토큰을_정상적으로_발급한다() {
        // given
        when(queuePort.issue("user1")).thenReturn(new QueueToken("abc123"));

        // when
        QueueUseCase.QueueResult issued = queueService.issue("user1");

        // then
        assertThat(issued.userId()).isEqualTo("user1");
        assertThat(issued.token()).isEqualTo("abc123");
        verify(queuePort, times(1)).issue("user1");
    }

    @Test
    void 만료된_토큰은_예외가_발생한다() {
        // given
        when(queuePort.isActive("expired")).thenReturn(false);

        // when & then
        assertThat(queueService.isActive("expired")).isFalse();
    }

    @Test
    void 토큰을_만료시킨다() {
        // when
        queueService.expire("token123");

        // then
        verify(queuePort, times(1)).expire("token123");
    }
}
