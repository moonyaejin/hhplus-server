package kr.hhplus.be.server.domain.reservation.service;

import java.time.LocalDate;

public final class ReservationPolicy {
    private ReservationPolicy() {}

    public static final int HOLD_SECONDS = 600; // 좌석 홀드 시간(초). 현재 10분.

    public static long priceOf(LocalDate date, int seatNo) {
        if (seatNo <= 10) {
            return 110_000L; // 앞자리 10석은 프리미엄
        }
        return 80_000L; // 그 외는 일반석
    }
}