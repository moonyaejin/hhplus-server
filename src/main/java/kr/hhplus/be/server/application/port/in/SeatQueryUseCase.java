package kr.hhplus.be.server.application.port.in;

import java.time.LocalDate;
import java.util.List;

public interface SeatQueryUseCase {
    List<SeatView> getSeatsStatus(Long concertId, LocalDate date);
    List<LocalDate> getAvailableDates(int days);

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
}