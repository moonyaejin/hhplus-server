package kr.hhplus.be.server.domain.reservation.service;

import java.time.LocalDate;

public final class ReservationPolicy {
    private ReservationPolicy() {}

    public static final int HOLD_SECONDS = 600; // 좌석 홀드 시간(초). 현재 10분.

    public static long priceOf(LocalDate date, int seatNo) { return 80_000L; } // 좌석 가격 정책. 현재는 고정가 80,000원. 필요 시 요일/구역별로 확장
}
