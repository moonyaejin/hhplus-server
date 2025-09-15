package kr.hhplus.be.server.domain.payment;

import java.util.Objects;

public record WalletId(String value) {
    public WalletId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("지갑 ID는 비어있을 수 없습니다");
        }
    }
}