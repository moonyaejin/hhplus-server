package kr.hhplus.be.server;

import kr.hhplus.be.server.application.usecase.reservation.ConfirmPaymentService;
import kr.hhplus.be.server.domain.reservation.model.exception.HoldNotFoundOrExpired;
import kr.hhplus.be.server.domain.reservation.model.exception.InsufficientBalance;
import kr.hhplus.be.server.domain.reservation.model.exception.SeatAlreadyConfirmed;
import kr.hhplus.be.server.application.usecase.reservation.ConfirmPaymentUseCase;
import kr.hhplus.be.server.application.port.out.ConfirmedReservationPort;
import kr.hhplus.be.server.application.port.out.QueuePort;
import kr.hhplus.be.server.application.port.out.SeatHoldPort;
import kr.hhplus.be.server.application.port.out.WalletPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ConfirmPaymentServiceTest {

    QueuePort queue = mock(QueuePort.class);
    SeatHoldPort hold = mock(SeatHoldPort.class);
    ConfirmedReservationPort confirmed = mock(ConfirmedReservationPort.class);
    WalletPort wallet = mock(WalletPort.class);

    ConfirmPaymentService sut = new ConfirmPaymentService(queue, hold, confirmed, wallet);

    LocalDate date = LocalDate.now();
    ConfirmPaymentUseCase.Command cmd =
            new ConfirmPaymentUseCase.Command("t", date, 10, "idem-1");

    @BeforeEach
    void setUp() {
        // Given: 토큰은 유효하고, 해당 토큰의 사용자 식별 가능
        when(queue.isActive("t")).thenReturn(true);
        when(queue.userIdOf("t")).thenReturn("user-1");
    }

    @Test
    void 성공플로우_hold있음_pay_insert_expire호출() {
        // given: 좌석은 내가 홀드했고, 아직 확정된 적 없음, 지갑 결제도 성공
        when(hold.isHeldBy(date, 10, "user-1")).thenReturn(true);
        when(confirmed.exists(date, 10)).thenReturn(false);
        when(wallet.pay("user-1", 80_000L, "idem-1")).thenReturn(920_000L); // 차감 후 잔액 예시
        when(confirmed.insert(eq(date), eq(10), eq("user-1"), eq(80_000L), any()))
                .thenReturn(123L);

        // when: 결제 확정을 시도
        var r = sut.confirm(cmd);

        // then: 예약 id와 잔액이 기대대로 나오고,
        assertThat(r.reservationId()).isEqualTo(123L);
        assertThat(r.balance()).isEqualTo(920_000L);

        // 실제로 pay/insert/expire 메서드가 호출되었는지도 검증
        verify(wallet).pay("user-1", 80_000L, "idem-1");
        verify(confirmed).insert(eq(date), eq(10), eq("user-1"), eq(80_000L), any());
        verify(queue).expire("t");
    }

    @Test
    void 실패_hold없거나만료_HoldNotFoundOrExpired() {
        // given: 좌석을 내가 홀드하지 않았다고 가정
        when(hold.isHeldBy(date, 10, "user-1")).thenReturn(false);

        // when & then: 예외가 터지고, 지갑 차감은 아예 시도되지 않음
        assertThatThrownBy(() -> sut.confirm(cmd))
                .isInstanceOf(HoldNotFoundOrExpired.class);

        verify(wallet, never()).pay(anyString(), anyLong(), anyString());
    }

    @Test
    void 실패_이미확정좌석_SeatAlreadyConfirmed() {
        // given: 내가 홀드했지만 이미 다른 사람에 의해 확정된 상태
        when(hold.isHeldBy(date, 10, "user-1")).thenReturn(true);
        when(confirmed.exists(date, 10)).thenReturn(true);

        // when & then: 이미 확정 예외 발생, 지갑 차감은 없음
        assertThatThrownBy(() -> sut.confirm(cmd))
                .isInstanceOf(SeatAlreadyConfirmed.class);

        verify(wallet, never()).pay(anyString(), anyLong(), anyString());
    }

    @Test
    void 실패_잔액부족_wallet에서예외전파() {
        // given: 홀드도 있고 확정도 안됐지만, 결제 시 잔액 부족 예외 발생
        when(hold.isHeldBy(date, 10, "user-1")).thenReturn(true);
        when(confirmed.exists(date, 10)).thenReturn(false);
        when(wallet.pay(anyString(), anyLong(), anyString()))
                .thenThrow(new InsufficientBalance());

        // when & then: InsufficientBalance 예외 전파, 예약 insert 호출되지 않음
        assertThatThrownBy(() -> sut.confirm(cmd))
                .isInstanceOf(InsufficientBalance.class);

        verify(confirmed, never()).insert(any(), anyInt(), anyString(), anyLong(), any());
    }

    @Test
    void 멱등성_두번째호출은_이미확정되어_wallet_pay_다시안불림() {
        // 1차 호출: 정상 플로우
        when(hold.isHeldBy(date, 10, "user-1")).thenReturn(true);
        when(confirmed.exists(date, 10)).thenReturn(false); // 첫 호출은 미확정
        when(wallet.pay(anyString(), anyLong(), anyString())).thenReturn(900_000L);
        when(confirmed.insert(eq(date), eq(10), eq("user-1"), eq(80_000L), any()))
                .thenReturn(1L);

        sut.confirm(cmd);  // 첫 번째 호출 성공

        // 2차 호출: 이미 확정된 상태로 바꿔서 재시도
        reset(wallet); // 호출 검증을 새로 하기 위해 mock 리셋
        when(hold.isHeldBy(date, 10, "user-1")).thenReturn(true);
        when(confirmed.exists(date, 10)).thenReturn(true); // 이미 확정

        // when & then: 이미 확정 예외 발생, 지갑은 다시 차감되지 않음
        assertThatThrownBy(() -> sut.confirm(cmd))
                .isInstanceOf(SeatAlreadyConfirmed.class);

        // 멱등성: 지갑 결제는 다시 일어나면 안 됨(중복 결제 방지)
        verify(wallet, never()).pay(anyString(), anyLong(), anyString());

        // 이미 확정 상태이므로 insert/expire 같은 후속 처리도 없음
        verify(confirmed, never()).insert(any(), anyInt(), anyString(), anyLong(), any());
        verify(queue, never()).expire(anyString());
    }
}
