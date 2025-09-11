package kr.hhplus.be.server.domain.concert;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ConcertScheduleRepository {
    Optional<ConcertSchedule> findById(ConcertScheduleId id);
    List<ConcertSchedule> findByConcertId(ConcertId concertId);
    List<LocalDate> findAvailableDates(int days);
}