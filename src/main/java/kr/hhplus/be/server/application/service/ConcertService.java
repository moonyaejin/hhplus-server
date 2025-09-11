package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.ConcertUseCase;
import kr.hhplus.be.server.application.port.out.ConcertPort;
import kr.hhplus.be.server.application.port.out.ConcertSchedulePort;
import kr.hhplus.be.server.application.port.out.ConfirmedReservationPort;
import kr.hhplus.be.server.application.port.out.SeatQueryPort;
import kr.hhplus.be.server.domain.concert.Concert;
import kr.hhplus.be.server.domain.concert.ConcertSchedule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConcertService implements ConcertUseCase {

    private final ConcertPort concertPort;
    private final ConcertSchedulePort concertSchedulePort;
    private final SeatQueryPort seatQueryPort;
    private final ConfirmedReservationPort confirmedReservationPort;

    // ConcertUseCase 인터페이스 구현 메서드들
    @Override
    public List<ConcertInfo> getAllConcerts() {
        List<Concert> concerts = concertPort.findAll();

        return concerts.stream()
                .map(concert -> new ConcertInfo(
                        concert.getId().value(),
                        concert.getTitle(),
                        concert.getDescription()
                ))
                .toList();
    }

    @Override
    public List<LocalDate> getAvailableDates(int days) {
        return concertSchedulePort.findAvailableDates(days);
    }

    @Override
    public ScheduleInfo getConcertSchedule(Long concertId, LocalDate date) {
        var schedule = concertSchedulePort.findByConcertIdAndConcertDate(concertId, date)
                .orElseThrow(() -> new RuntimeException("해당 날짜의 콘서트 스케줄을 찾을 수 없습니다"));

        // 좌석 상태 조회
        List<SeatQueryPort.SeatView> seatViews = seatQueryPort.getSeatsStatus(concertId, date);

        // 예약 가능한 좌석 번호만 추출
        List<Integer> availableSeats = seatViews.stream()
                .filter(seat -> seat.status() == SeatQueryPort.SeatStatus.FREE)
                .map(SeatQueryPort.SeatView::seatNumber)
                .toList();

        return new ScheduleInfo(
                schedule.getId().value(),
                schedule.getConcertId().value(),
                schedule.getConcertDate(),
                schedule.getTotalSeats(),
                availableSeats
        );
    }

    // 테스트 코드에서 사용하는 메서드들 추가
    public List<Concert> listConcerts() {
        return concertPort.findAll();
    }

    public Optional<Concert> getConcert(Long id) {
        return concertPort.findById(id);
    }

    public List<Integer> listAvailableSeats(Long concertId, LocalDate date) {
        ConcertSchedule schedule = concertSchedulePort.findByConcertIdAndConcertDate(concertId, date)
                .orElseThrow(() -> new IllegalArgumentException("해당 날짜의 콘서트 스케줄을 찾을 수 없습니다"));

        // 확정 예약된 좌석 번호들 조회
        List<Integer> reservedSeats = confirmedReservationPort.findSeatNosByConcertDate(date);

        // 전체 좌석에서 예약된 좌석 제외
        return IntStream.rangeClosed(1, schedule.getTotalSeats())
                .filter(seatNo -> !reservedSeats.contains(seatNo))
                .boxed()
                .toList();
    }
}