package kr.hhplus.be.server.application.port.out;

import java.time.LocalDate;
import java.util.List;

/**
 * 좌석 조회 전용 포트
 */
public interface SeatQueryPort {

    // 좌석 상태 정보
    record SeatView(
            int seatNumber,
            SeatStatus status,
            Long remainingSeconds  // HELD 상태일 때만 값 존재
    ) {}

    // 좌석 상태 enum
    enum SeatStatus {
        FREE("예약가능"),
        HELD("임시배정중"),
        CONFIRMED("예약완료");

        private final String displayName;

        SeatStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * 특정 콘서트 회차의 좌석 상태 조회
     */
    List<SeatView> getSeatsStatus(Long concertId, LocalDate date);

    /**
     * 예약 가능한 날짜 목록 조회
     */
    List<LocalDate> getAvailableDates(int days);
}