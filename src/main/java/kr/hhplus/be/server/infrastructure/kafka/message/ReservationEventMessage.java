package kr.hhplus.be.server.infrastructure.kafka.message;

import java.time.LocalDateTime;

public record ReservationEventMessage(
        String eventType,      // "CONFIRMED" | "CANCELLED"
        String reservationId,
        String userId,
        Long scheduleId,
        Integer seatNumber,
        Long price,
        LocalDateTime eventTime
) {
    public static ReservationEventMessage confirmed(
            String reservationId,
            String userId,
            Long scheduleId,
            Integer seatNumber,
            Long price,
            LocalDateTime confirmedAt
    ) {
        return new ReservationEventMessage(
                "CONFIRMED",
                reservationId,
                userId,
                scheduleId,
                seatNumber,
                price,
                confirmedAt
        );
    }

    public static ReservationEventMessage cancelled(
            String reservationId,
            String userId,
            Long scheduleId,
            Integer seatNumber,
            Long price,
            LocalDateTime cancelledAt
    ) {
        return new ReservationEventMessage(
                "CANCELLED",
                reservationId,
                userId,
                scheduleId,
                seatNumber,
                price,
                cancelledAt
        );
    }
}