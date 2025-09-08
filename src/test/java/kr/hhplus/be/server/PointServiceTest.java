package kr.hhplus.be.server;

import kr.hhplus.be.server.application.port.out.WalletPort;
import kr.hhplus.be.server.application.usecase.balance.PointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PointServiceTest {

    private WalletPort walletPort;
    private PointService pointService;

    @BeforeEach
    void setUp() {
        walletPort = mock(WalletPort.class);
        pointService = new PointService(walletPort);
    }

    @Test
    void 정상적으로_포인트를_충전한다() {
        when(walletPort.topUp("user1", 1000L, "key1")).thenReturn(2000L);

        long balance = pointService.charge("user1", 1000L, "key1");

        assertThat(balance).isEqualTo(2000L);
        verify(walletPort).topUp("user1", 1000L, "key1");
    }

    @Test
    void 음수_포인트_충전시_예외발생() {
        assertThrows(IllegalArgumentException.class,
                () -> pointService.charge("user1", -500L, "key1"));
    }
}
