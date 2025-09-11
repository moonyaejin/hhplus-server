package kr.hhplus.be.server.web.reservation.dto;

import jakarta.validation.constraints.*;

public record TemporaryAssignRequest(
        @NotNull @Positive Long concertScheduleId,
        @NotNull @Min(1) @Max(50) Integer seatNumber
) {}