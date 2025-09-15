package kr.hhplus.be.server.domain.reservation;

public record SeatNumber(int value) {

    public SeatNumber {
        if (value < 1 || value > 50) {
            throw new IllegalArgumentException("좌석 번호는 1~50 사이여야 합니다");
        }
    }

    public static SeatNumber of(int value) {
        return new SeatNumber(value);
    }

    public boolean isPremiumSeat() {
        return value <= 10; // 1~10번은 프리미엄 좌석
    }
}