package kr.hhplus.be.server.web.reservation.dto;

import java.time.LocalDateTime;

public record ConfirmReservationResponse(
        String reservationId,
        long remainingBalance,
        LocalDateTime confirmedAt
) {}