package kr.hhplus.be.server.application.dto.reservation;

import java.time.Instant;

public record HoldResponseDto(long price, Instant holdExpiresAt) {}