package kr.hhplus.be.server.web.wallet.dto;

public record ChargeResponse(
        String userId,
        Long balance
) {}