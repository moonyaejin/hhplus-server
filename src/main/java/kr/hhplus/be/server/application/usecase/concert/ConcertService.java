package kr.hhplus.be.server.application.usecase.concert;

import kr.hhplus.be.server.application.port.in.ConcertUseCase;
import kr.hhplus.be.server.domain.concert.Concert;
import kr.hhplus.be.server.domain.concert.ConcertRepository;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertScheduleJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.ConfirmedReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class ConcertService implements ConcertUseCase {

    private final ConcertRepository concerts;              // 도메인 포트
    private final ConcertScheduleJpaRepository schedules;
    private final ConfirmedReservationJpaRepository confirmed;// 확정 좌석 조회

    @Override
    public List<Concert> listConcerts() {
        return concerts.findAll();
    }

    @Override
    public Optional<Concert> getConcert(Long concertId) {
        return concerts.findById(concertId);
    }

    @Override
    public List<LocalDate> listAvailableDates(Long concertId, int days) {
        var start = LocalDate.now();
        return IntStream.range(0, days).mapToObj(i -> start.plusDays(i)).toList();
    }

    @Override
    public List<Integer> listAvailableSeats(Long concertId, LocalDate date) {
        var schedule = schedules.findByConcertIdAndConcertDate(concertId, date)
                .orElseThrow(() -> new IllegalArgumentException("schedule not found"));
        var taken = confirmed.findSeatNosByConcertDate(date);
        return IntStream.rangeClosed(1, schedule.getSeatCount())
                .filter(n -> !taken.contains(n))
                .boxed()
                .toList();
    }
}
