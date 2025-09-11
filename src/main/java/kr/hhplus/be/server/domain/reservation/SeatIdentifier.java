package kr.hhplus.be.server.domain.reservation;

import kr.hhplus.be.server.domain.concert.ConcertScheduleId;

/**
 * 특정 콘서트 회차의 특정 좌석을 식별하는 Value Object
 */
public record SeatIdentifier(
        ConcertScheduleId scheduleId,
        SeatNumber seatNumber
) {

    public SeatIdentifier {
        if (scheduleId == null) {
            throw new IllegalArgumentException("콘서트 스케줄 ID는 필수입니다");
        }
        if (seatNumber == null) {
            throw new IllegalArgumentException("좌석 번호는 필수입니다");
        }
    }

    public static SeatIdentifier of(ConcertScheduleId scheduleId, SeatNumber seatNumber) {
        return new SeatIdentifier(scheduleId, seatNumber);
    }

    public static SeatIdentifier of(Long scheduleId, int seatNumber) {
        return new SeatIdentifier(
                new ConcertScheduleId(scheduleId),
                new SeatNumber(seatNumber)
        );
    }

    // 편의 메서드
    public boolean isPremiumSeat() {
        return seatNumber.isPremiumSeat();
    }

    public String toDisplayString() {
        return String.format("스케줄[%d] - 좌석[%d]",
                scheduleId.value(), seatNumber.value());
    }
}