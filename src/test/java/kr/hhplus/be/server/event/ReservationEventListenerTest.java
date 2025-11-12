package kr.hhplus.be.server.event;

import kr.hhplus.be.server.application.event.ReservationConfirmedEvent;
import kr.hhplus.be.server.application.event.ReservationEventListener;
import kr.hhplus.be.server.application.port.in.RankingUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 예약 이벤트 리스너 단위 테스트
 */
@ExtendWith(MockitoExtension.class)  // ← Spring 없이 순수 Mockito!
@DisplayName("예약 이벤트 리스너 단위 테스트")
class ReservationEventListenerTest {

    @Mock  // ← @MockBean 대신 @Mock 사용
    private RankingUseCase rankingUseCase;

    @InjectMocks  // ← 자동으로 Mock 주입
    private ReservationEventListener listener;

    @Test
    @DisplayName("랭킹 업데이트 - 정상 동작")
    void updateRanking_Success() {
        // given
        ReservationConfirmedEvent event = ReservationConfirmedEvent.of(
                "reservation-123",
                "user-456",
                1L,
                10,
                100_000L,
                LocalDateTime.now()
        );

        // when
        listener.updateRanking(event);

        // then
        verify(rankingUseCase, times(1))
                .trackReservation(eq(1L), eq(1));
    }

    @Test
    @DisplayName("랭킹 업데이트 - 예외 발생해도 처리 완료")
    void updateRanking_WithException_DoesNotThrow() {
        // given
        ReservationConfirmedEvent event = ReservationConfirmedEvent.of(
                "reservation-123",
                "user-456",
                1L,
                10,
                100_000L,
                LocalDateTime.now()
        );

        // rankingUseCase 호출 시 예외 발생
        doThrow(new RuntimeException("Redis 연결 실패"))
                .when(rankingUseCase)
                .trackReservation(anyLong(), anyInt());

        // when & then - 예외가 발생해도 메서드는 정상 종료
        listener.updateRanking(event);

        // 한 번은 호출됨
        verify(rankingUseCase, times(1))
                .trackReservation(anyLong(), anyInt());
    }

    @Test
    @DisplayName("데이터 플랫폼 전송 - 정상 동작")
    void sendToDataPlatform_Success() {
        // given
        ReservationConfirmedEvent event = ReservationConfirmedEvent.of(
                "reservation-123",
                "user-456",
                1L,
                10,
                100_000L,
                LocalDateTime.now()
        );

        // when
        listener.sendToDataPlatform(event);

        // then - 예외 없이 완료되면 성공
        // Mock API 호출은 실제로 아무것도 안 하므로 예외만 안 나면 OK
    }

    @Test
    @DisplayName("여러 이벤트 연속 처리")
    void processMultipleEvents() {
        // given
        for (int i = 1; i <= 5; i++) {
            ReservationConfirmedEvent event = ReservationConfirmedEvent.of(
                    "reservation-" + i,
                    "user-" + i,
                    (long) i,
                    10 + i,
                    100_000L * i,
                    LocalDateTime.now()
            );

            // when
            listener.updateRanking(event);
            listener.sendToDataPlatform(event);
        }

        // then
        verify(rankingUseCase, times(5))
                .trackReservation(anyLong(), anyInt());
    }
}