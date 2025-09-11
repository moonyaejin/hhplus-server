package kr.hhplus.be.server;

import kr.hhplus.be.server.application.port.in.QueueUseCase.*;
import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.application.service.QueueService;
import kr.hhplus.be.server.domain.queue.model.QueueToken;
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

    private QueueService queueService;

    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String TOKEN_VALUE = "test-token-value";

    @BeforeEach
    void setUp() {
        queueService = new QueueService(queuePort);
    }

    @Test
    @DisplayName("토큰 발급 - 성공")
    void issueToken_Success() {
        // given
        IssueTokenCommand command = new IssueTokenCommand(USER_ID);
        QueueToken expectedToken = new QueueToken(TOKEN_VALUE);

        when(queuePort.issue(USER_ID)).thenReturn(expectedToken);

        // when
        TokenInfo result = queueService.issueToken(command);

        // then
        assertThat(result).isNotNull();
        assertThat(result.token()).isEqualTo(TOKEN_VALUE);
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.waitingNumber()).isEqualTo(0L);
        assertThat(result.expiresAt()).isNotNull();

        // 검증: 토큰 발급 요청
        verify(queuePort).issue(USER_ID);
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
    @DisplayName("토큰 정보 조회 - 만료된 토큰")
    void getTokenInfo_Expired() {
        // given
        when(queuePort.isActive(TOKEN_VALUE)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> queueService.getTokenInfo(TOKEN_VALUE))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("유효하지 않거나 만료된 토큰입니다");

        // 검증: userIdOf 호출하지 않음
        verify(queuePort, never()).userIdOf(anyString());
    }

    @Test
    @DisplayName("토큰 만료 처리")
    void expireToken_Success() {
        // given & when
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
    @DisplayName("토큰으로 사용자 ID 조회 - 성공")
    void getUserIdByToken_Success() {
        // given
        when(queuePort.isActive(TOKEN_VALUE)).thenReturn(true);
        when(queuePort.userIdOf(TOKEN_VALUE)).thenReturn(USER_ID);

        // when
        String result = queueService.getUserIdByToken(TOKEN_VALUE);

        // then
        assertThat(result).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("토큰으로 사용자 ID 조회 - 만료된 토큰")
    void getUserIdByToken_ExpiredToken() {
        // given
        when(queuePort.isActive(TOKEN_VALUE)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> queueService.getUserIdByToken(TOKEN_VALUE))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("유효하지 않거나 만료된 토큰입니다");

        // 검증: userIdOf 호출하지 않음
        verify(queuePort, never()).userIdOf(anyString());
    }

    @Test
    @DisplayName("null 사용자 ID로 토큰 발급 시도")
    void issueToken_NullUserId() {
        // given
        IssueTokenCommand command = new IssueTokenCommand(null);

        // when & then
        assertThatThrownBy(() -> queueService.issueToken(command))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("빈 문자열 토큰으로 조회 시도")
    void getTokenInfo_EmptyToken() {
        // given
        String emptyToken = "";
        when(queuePort.isActive(emptyToken)).thenReturn(false);

        // when & then
        assertThatThrownBy(() -> queueService.getTokenInfo(emptyToken))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("유효하지 않거나 만료된 토큰입니다");
    }
}