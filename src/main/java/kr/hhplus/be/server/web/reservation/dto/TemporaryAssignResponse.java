package kr.hhplus.be.server.web.reservation.dto;

import java.time.LocalDateTime;

public record TemporaryAssignResponse(
        String reservationId,
        long price,
        LocalDateTime expirationTime
) {}