package kr.hhplus.be.server.application.port.in;

import kr.hhplus.be.server.domain.concert.Concert;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ConcertUseCase {
    List<Concert> listConcerts();
    Optional<Concert> getConcert(Long concertId);

    List<LocalDate> listAvailableDates(Long concertId, int days);
    List<Integer> listAvailableSeats(Long concertId, LocalDate date);
}
