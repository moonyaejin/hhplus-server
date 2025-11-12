package kr.hhplus.be.server.application.event;

import java.time.LocalDateTime;

/**
 * 예약 확정 완료 이벤트
 *
 * - 예약 확정 트랜잭션 커밋 후 발행
 * - 데이터 플랫폼 전송 등 부가 기능 트리거
 */
public record ReservationConfirmedEvent(
        String reservationId,
        String userId,
        Long scheduleId,
        Integer seatNumber,
        Long price,
        LocalDateTime confirmedAt
) {
    /**
     * 예약 정보로부터 이벤트 생성
     */
    public static ReservationConfirmedEvent of(
            String reservationId,
            String userId,
            Long scheduleId,
            Integer seatNumber,
            Long price,
            LocalDateTime confirmedAt
    ) {
        return new ReservationConfirmedEvent(
                reservationId,
                userId,
                scheduleId,
                seatNumber,
                price,
                confirmedAt
        );
    }
}