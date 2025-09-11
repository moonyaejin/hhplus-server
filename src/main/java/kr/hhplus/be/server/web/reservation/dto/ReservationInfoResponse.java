package kr.hhplus.be.server.web.reservation.dto;

import java.time.LocalDateTime;

public record ReservationInfoResponse(
        String reservationId,
        String userId,
        Long concertScheduleId,
        int seatNumber,
        String status,
        long price,
        LocalDateTime temporaryAssignedAt,
        LocalDateTime confirmedAt,
        LocalDateTime expirationTime
) {}