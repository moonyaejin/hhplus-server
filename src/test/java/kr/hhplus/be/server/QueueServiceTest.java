package kr.hhplus.be.server;

import kr.hhplus.be.server.application.port.in.QueueUseCase.*;
import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.application.service.QueueService;
import kr.hhplus.be.server.domain.queue.model.QueueToken;
import kr.hhplus.be.server.infrastructure.persistence.queue.redis.RedisQueueAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    @Mock
    private QueuePort queuePort;

    @Mock
    private RedisQueueAdapter redisQueueAdapter;

    private QueueService queueService;

    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String TOKEN_VALUE = "test-token-value";

    @BeforeEach
    void setUp() {
        queueService = new QueueService(queuePort);
    }

    @Test
    @DisplayName("토큰 발급 - 즉시 활성화")
    void issueToken_Immediate_Active() {
        // given
        QueueToken expectedToken = new QueueToken(TOKEN_VALUE);
        when(queuePort.issue(USER_ID)).thenReturn(expectedToken);
        when(queuePort.isActive(TOKEN_VALUE)).thenReturn(true);

        // when
        TokenInfo result = queueService.issueToken(new IssueTokenCommand(USER_ID));

        // then
        assertThat(result).isNotNull();
        assertThat(result.token()).isEqualTo(TOKEN_VALUE);
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.waitingNumber()).isEqualTo(0L);
        assertThat(result.expiresAt()).isNotNull();

        verify(queuePort).issue(USER_ID);
        verify(queuePort).isActive(TOKEN_VALUE);
    }

    @Test
    @DisplayName("토큰 발급 - 대기열 진입")
    void issueToken_Enter_WaitingQueue() {
        // given
        QueueService serviceWithRedis = new QueueService(redisQueueAdapter);
        QueueToken expectedToken = new QueueToken(TOKEN_VALUE);

        when(redisQueueAdapter.issue(USER_ID)).thenReturn(expectedToken);
        when(redisQueueAdapter.isActive(TOKEN_VALUE)).thenReturn(false);
        when(redisQueueAdapter.getWaitingPosition(TOKEN_VALUE)).thenReturn(5L);

        // when
        TokenInfo result = serviceWithRedis.issueToken(new IssueTokenCommand(USER_ID));

        // then
        assertThat(result.status()).isEqualTo("WAITING");
        assertThat(result.waitingNumber()).isEqualTo(5L);
        assertThat(result.token()).isEqualTo(TOKEN_VALUE);
    }

    @Test
    @DisplayName("토큰 정보 조회 - 활성 토큰")
    void getTokenInfo_Active() {
        // given
        when(queuePort.isActive(TOKEN_VALUE)).thenReturn(true);
        when(queuePort.userIdOf(TOKEN_VALUE)).thenReturn(USER_ID);

        // when
        TokenInfo result = queueService.getTokenInfo(TOKEN_VALUE);

        // then
        assertThat(result).isNotNull();
        assertThat(result.token()).isEqualTo(TOKEN_VALUE);
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.waitingNumber()).isEqualTo(0L);
    }

    @Test
    @DisplayName("토큰 정보 조회 - 대기 중인 토큰")
    void getTokenInfo_Waiting() {
        // given
        QueueService serviceWithRedis = new QueueService(redisQueueAdapter);

        when(redisQueueAdapter.isActive(TOKEN_VALUE)).thenReturn(false);
        when(redisQueueAdapter.getWaitingPosition(TOKEN_VALUE)).thenReturn(3L);
        when(redisQueueAdapter.userIdOf(TOKEN_VALUE)).thenReturn(USER_ID);

        // when
        TokenInfo result = serviceWithRedis.getTokenInfo(TOKEN_VALUE);

        // then
        assertThat(result.status()).isEqualTo("WAITING");
        assertThat(result.waitingNumber()).isEqualTo(3L);
        assertThat(result.userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("토큰 정보 조회 - 만료된 토큰")
    void getTokenInfo_Expired() {
        // given
        QueueService serviceWithRedis = new QueueService(redisQueueAdapter);

        when(redisQueueAdapter.isActive(TOKEN_VALUE)).thenReturn(false);
        when(redisQueueAdapter.getWaitingPosition(TOKEN_VALUE)).thenReturn(null);

        // when & then
        assertThatThrownBy(() -> serviceWithRedis.getTokenInfo(TOKEN_VALUE))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("유효하지 않거나 만료된 토큰입니다");
    }

    @Test
    @DisplayName("토큰 만료 처리")
    void expireToken_Success() {
        // when
        queueService.expireToken(TOKEN_VALUE);

        // then
        verify(queuePort).expire(TOKEN_VALUE);
    }

    @Test
    @DisplayName("토큰 활성 상태 확인 - 활성")
    void isTokenActive_True() {
        // given
        when(queuePort.isActive(TOKEN_VALUE)).thenReturn(true);

        // when
        boolean result = queueService.isTokenActive(TOKEN_VALUE);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("토큰 활성 상태 확인 - 비활성")
    void isTokenActive_False() {
        // given
        when(queuePort.isActive(TOKEN_VALUE)).thenReturn(false);

        // when
        boolean result = queueService.isTokenActive(TOKEN_VALUE);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("대기열 처리 - 슬롯 가용시 활성화")
    void processQueue_ActivatesWaitingUsers() {
        // given
        QueueService serviceWithRedis = new QueueService(redisQueueAdapter);

        when(redisQueueAdapter.getActiveCount()).thenReturn(95L);
        // 100 - 95 = 5개 슬롯 가용

        // when
        serviceWithRedis.processQueue();

        // then
        verify(redisQueueAdapter).activateNextUsers(5);
    }

    @Test
    @DisplayName("대기열 처리 - 슬롯 없을 때")
    void processQueue_NoAvailableSlots() {
        // given
        QueueService serviceWithRedis = new QueueService(redisQueueAdapter);

        when(redisQueueAdapter.getActiveCount()).thenReturn(100L);
        // 가용 슬롯 없음

        // when
        serviceWithRedis.processQueue();

        // then
        verify(redisQueueAdapter, never()).activateNextUsers(anyInt());
    }

    @Test
    @DisplayName("토큰으로 사용자 ID 조회 - 성공")
    void getUserIdByToken_Success() {
        // given
        when(queuePort.userIdOf(TOKEN_VALUE)).thenReturn(USER_ID);

        // when
        String result = queueService.getUserIdByToken(TOKEN_VALUE);

        // then
        assertThat(result).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("토큰으로 사용자 ID 조회 - 유효하지 않은 토큰")
    void getUserIdByToken_InvalidToken() {
        // given
        when(queuePort.userIdOf(TOKEN_VALUE)).thenReturn(null);

        // when & then
        assertThatThrownBy(() -> queueService.getUserIdByToken(TOKEN_VALUE))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("유효하지 않거나 만료된 토큰입니다");
    }
}