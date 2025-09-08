package kr.hhplus.be.server.application.dto.payment;

import jakarta.validation.constraints.Positive;

public record MoneyRequest(@Positive long amount) {}