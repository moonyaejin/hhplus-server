package kr.hhplus.be.server.application.port.out;

import java.time.LocalDate;
import java.util.List;

public interface SeatQueryPort {

    // SeatView와 SeatStatus는 UseCase에서 가져옴
    List<kr.hhplus.be.server.application.port.in.SeatQueryUseCase.SeatView> getSeatsStatus(Long concertId, LocalDate date);
    List<LocalDate> getAvailableDates(int days);
}