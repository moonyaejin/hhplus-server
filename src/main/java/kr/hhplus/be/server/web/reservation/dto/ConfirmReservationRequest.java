package kr.hhplus.be.server.web.reservation.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmReservationRequest(
        @NotBlank String reservationId
) {}