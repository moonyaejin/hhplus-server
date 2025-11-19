package kr.hhplus.be.server.application.event;

import java.time.LocalDateTime;

/**
 * 예약 취소 완료 이벤트
 *
 * 발행 시점: 예약 취소 트랜잭션 커밋 후 (AFTER_COMMIT)
 *
 * 처리 작업:
 * - 랭킹 시스템에서 예약 수 차감
 * - 데이터 플랫폼으로 취소 정보 전송
 * - (향후 확장) 알림톡, 이메일 발송 등
 *
 * 일관성 유지:
 * - ReservationConfirmedEvent와 동일한 구조
 * - 이벤트 기반 아키텍처의 일관된 패턴 적용
 */
public record ReservationCancelledEvent(
        String reservationId,
        String userId,
        Long scheduleId,
        Integer seatNumber,
        Long price,
        LocalDateTime cancelledAt
) {
    /**
     * 예약 정보로부터 취소 이벤트 생성
     */
    public static ReservationCancelledEvent of(
            String reservationId,
            String userId,
            Long scheduleId,
            Integer seatNumber,
            Long price,
            LocalDateTime cancelledAt
    ) {
        return new ReservationCancelledEvent(
                reservationId,
                userId,
                scheduleId,
                seatNumber,
                price,
                cancelledAt
        );
    }
}