package kr.hhplus.be.server;

import kr.hhplus.be.server.reservation.application.HoldSeatService;
import kr.hhplus.be.server.reservation.port.in.HoldSeatUseCase;
import kr.hhplus.be.server.reservation.port.out.ConfirmedReservationPort;
import kr.hhplus.be.server.reservation.port.out.QueuePort;
import kr.hhplus.be.server.reservation.port.out.SeatHoldPort;
import org.junit.Test;

import java.time.LocalDate;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

public class HoldSeatServiceTest {

    QueuePort queue = mock(QueuePort.class);
    SeatHoldPort hold = mock(SeatHoldPort.class);
    ConfirmedReservationPort confirmed = mock(ConfirmedReservationPort.class);
    HoldSeatService sut = new HoldSeatService(queue, hold, confirmed);

    @Test
    public void hold_success() {
        var cmd = new HoldSeatUseCase.Command("t", LocalDate.now(), 10);
        when(queue.isActive("t")).thenReturn(true);
        when(queue.userIdOf("t")).thenReturn("user-1");
        when(confirmed.exists(any(), anyInt())).thenReturn(false);
        when(hold.tryHold(any(), anyInt(), eq("user-1"), anyInt())).thenReturn(true);

        var r = sut.hold(cmd);
        assertThat(r.success()).isTrue();
        assertThat(r.price()).isEqualTo(80000L);
    }
}
