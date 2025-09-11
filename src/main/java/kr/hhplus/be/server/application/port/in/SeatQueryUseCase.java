package kr.hhplus.be.server.application.port.in;

import kr.hhplus.be.server.application.port.out.SeatQueryPort;

import java.time.LocalDate;
import java.util.List;

public interface SeatQueryUseCase {
    List<SeatQueryPort.SeatView> getSeatsStatus(Long concertId, LocalDate date);  // 메서드명 통일
    List<LocalDate> getAvailableDates(int days);
}