package kr.hhplus.be.server.application.dto;

import java.time.Instant;

public record ReservationResponse(
        Long reservationId,
        long price,
        Instant paidAt
) {}
