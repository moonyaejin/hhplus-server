package kr.hhplus.be.server.application.dto.reservation;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record ConfirmRequestDto(
        @NotNull @FutureOrPresent LocalDate date,
        @Min(1) int seatNo
) {}