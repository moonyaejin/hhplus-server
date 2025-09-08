package kr.hhplus.be.server;

import kr.hhplus.be.server.application.usecase.reservation.HoldSeatService;
import kr.hhplus.be.server.domain.reservation.model.exception.SeatAlreadyConfirmed;
import kr.hhplus.be.server.domain.reservation.model.exception.SeatAlreadyHeld;
import kr.hhplus.be.server.application.usecase.reservation.HoldSeatUseCase;
import kr.hhplus.be.server.application.port.out.ConfirmedReservationPort;
import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.application.port.out.SeatHoldPort;

import kr.hhplus.be.server.domain.reservation.service.ReservationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HoldSeatServiceTest {

    QueuePort queue = mock(QueuePort.class);
    SeatHoldPort hold = mock(SeatHoldPort.class);
    ConfirmedReservationPort confirmed = mock(ConfirmedReservationPort.class);
    HoldSeatService sut = new HoldSeatService(queue, hold, confirmed);

    LocalDate date = LocalDate.now();
    HoldSeatUseCase.Command cmd = new HoldSeatUseCase.Command("t", date, 10);

    // given: 항상 토큰은 활성 상태이고, "t" 토큰은 "user-1" 유저를 가리키도록 설정
    @BeforeEach
    void setUp() {
        when(queue.isActive("t")).thenReturn(true);
        when(queue.userIdOf("t")).thenReturn("user-1");
    }

    @Test
    void 성공_홀드성공_가격반환() {
        // given: 아직 확정된 예약이 없고 좌석 홀드 시도가 성공한다고 가정
        when(confirmed.exists(date, 10)).thenReturn(false);
        when(hold.tryHold(eq(date), eq(10), eq("user-1"), anyInt())).thenReturn(true);

        // when
        var r = sut.hold(cmd);

        // then: seatNo=10 이므로 가격은 110,000원이어야 함
        assertThat(r.success()).isTrue();
        assertThat(r.price()).isEqualTo(110_000L);
        verify(hold).tryHold(eq(date), eq(10), eq("user-1"), eq(ReservationPolicy.HOLD_SECONDS));
    }

    @Test
    void 실패_이미확정좌석이면_SeatAlreadyConfirmed() {
        // given: 해당 좌석이 이미 확정된 상태
        when(confirmed.exists(date, 10)).thenReturn(true);

        // when & then: SeatAlreadyConfirmed 예외 발생
        assertThatThrownBy(() -> sut.hold(cmd))
                .isInstanceOf(SeatAlreadyConfirmed.class);

        // 이미 확정된 상태라서 tryHold는 아예 호출되지 않아야 함
        verify(hold, never()).tryHold(any(LocalDate.class), anyInt(), anyString(), anyInt());
    }

    @Test
    void 실패_이미홀드되어있으면_SeatAlreadyHeld() {
        // given: 아직 확정된 예약이 없는데 좌석 홀드 시도가 실패(이미 다른 사람이 홀드해서)
        when(confirmed.exists(date, 10)).thenReturn(false);
        when(hold.tryHold(any(LocalDate.class), anyInt(), anyString(), anyInt())).thenReturn(false);

        // when & then: SeatAlreadyHeld 예외 발생
        assertThatThrownBy(() -> sut.hold(cmd))
                .isInstanceOf(SeatAlreadyHeld.class);
    }
}
