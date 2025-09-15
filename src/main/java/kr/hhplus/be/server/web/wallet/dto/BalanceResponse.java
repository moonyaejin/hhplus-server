package kr.hhplus.be.server.web.wallet.dto;

public record BalanceResponse(
        String userId,
        Long balance
) {}