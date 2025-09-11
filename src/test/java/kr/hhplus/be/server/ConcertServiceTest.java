package kr.hhplus.be.server;

import kr.hhplus.be.server.application.port.out.ConfirmedReservationPort;
import kr.hhplus.be.server.application.port.out.SeatQueryPort;
import kr.hhplus.be.server.application.service.ConcertService;
import kr.hhplus.be.server.application.port.out.ConcertPort;
import kr.hhplus.be.server.application.port.out.ConcertSchedulePort;
import kr.hhplus.be.server.domain.concert.Concert;
import kr.hhplus.be.server.domain.concert.ConcertId;
import kr.hhplus.be.server.domain.concert.ConcertSchedule;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ConcertServiceTest {

    private ConcertPort concertPort;
    private ConcertSchedulePort schedulePort;
    private SeatQueryPort seatQueryPort;
    private ConfirmedReservationPort confirmedReservationPort;
    private ConcertService concertService;

    @BeforeEach
    void setUp() {
        concertPort = mock(ConcertPort.class);
        schedulePort = mock(ConcertSchedulePort.class);
        seatQueryPort = mock(SeatQueryPort.class);
        confirmedReservationPort = mock(ConfirmedReservationPort.class);
        concertService = new ConcertService(concertPort, schedulePort, seatQueryPort, confirmedReservationPort);
    }

    @Test
    void 모든_콘서트를_조회한다() {
        // given
        Concert concert = new Concert(new ConcertId(1L), "테스트 공연"); // Value Object 사용
        when(concertPort.findAll()).thenReturn(List.of(concert));

        // when
        List<Concert> concerts = concertService.listConcerts();

        // then
        assertThat(concerts).hasSize(1);
        assertThat(concerts.get(0).getTitle()).isEqualTo("테스트 공연");
    }

    @Test
    void ID로_콘서트를_조회한다() {
        // given
        Concert concert = new Concert(new ConcertId(1L), "테스트 공연"); // Value Object 사용
        when(concertPort.findById(1L)).thenReturn(Optional.of(concert));

        // when
        Optional<Concert> result = concertService.getConcert(1L);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getTitle()).isEqualTo("테스트 공연");
    }

    @Test
    void 존재하지_않는_스케줄_조회시_예외가_발생한다() {
        // given
        LocalDate date = LocalDate.now();
        when(schedulePort.findByConcertIdAndConcertDate(1L, date)).thenReturn(Optional.empty());

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> concertService.listAvailableSeats(1L, date));
    }

    @Test
    void 예약된_좌석을_제외하고_남은_좌석을_조회한다() {
        // given
        LocalDate date = LocalDate.now();
        ConcertSchedule schedule = new ConcertSchedule(
                new ConcertScheduleId(1L),  // Value Object 사용
                new ConcertId(1L),          // Value Object 사용
                date,
                5  // seatCount=5
        );

        when(schedulePort.findByConcertIdAndConcertDate(1L, date))
                .thenReturn(Optional.of(schedule));
        when(confirmedReservationPort.findSeatNosByConcertDate(date))
                .thenReturn(List.of(2, 4)); // 2번, 4번 좌석 예약됨

        // when
        List<Integer> available = concertService.listAvailableSeats(1L, date);

        // then
        assertThat(available).containsExactly(1, 3, 5);
    }
}