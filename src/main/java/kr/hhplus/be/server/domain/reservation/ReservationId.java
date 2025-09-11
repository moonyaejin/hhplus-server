package kr.hhplus.be.server.domain.reservation;

import java.util.UUID;

public record ReservationId(String value) {

    public ReservationId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("예약 ID는 비어있을 수 없습니다");
        }
    }

    public static ReservationId generate() {
        return new ReservationId(UUID.randomUUID().toString());
    }

    public static ReservationId of(String value) {
        return new ReservationId(value);
    }
}