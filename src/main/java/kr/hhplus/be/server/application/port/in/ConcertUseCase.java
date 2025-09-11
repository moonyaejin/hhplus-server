package kr.hhplus.be.server.application.port.in;

import java.time.LocalDate;
import java.util.List;

public interface ConcertUseCase {

    record ConcertInfo(Long id, String title, String description) {}

    record ScheduleInfo(
            Long scheduleId,
            Long concertId,
            LocalDate concertDate,
            int totalSeats,
            List<Integer> availableSeats
    ) {}

    List<ConcertInfo> getAllConcerts();
    List<LocalDate> getAvailableDates(int days);
    ScheduleInfo getConcertSchedule(Long concertId, LocalDate date);
}