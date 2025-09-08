package kr.hhplus.be.server.application.dto.reservation;

import java.time.Instant;

public record ConfirmResponseDto(long reservationId, long balance, Instant paidAt) {}
