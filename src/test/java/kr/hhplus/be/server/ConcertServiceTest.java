package kr.hhplus.be.server;

import kr.hhplus.be.server.application.usecase.concert.ConcertService;
import kr.hhplus.be.server.domain.concert.Concert;
import kr.hhplus.be.server.domain.concert.ConcertRepository;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertScheduleJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertScheduleJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.ConfirmedReservationJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ConcertServiceTest {

    private ConcertRepository concertRepository;
    private ConcertScheduleJpaRepository scheduleRepository;
    private ConfirmedReservationJpaRepository confirmedReservationRepository;
    private ConcertService concertService;

    @BeforeEach
    void setUp() {
        concertRepository = mock(ConcertRepository.class);
        scheduleRepository = mock(ConcertScheduleJpaRepository.class);
        confirmedReservationRepository = mock(ConfirmedReservationJpaRepository.class);
        concertService = new ConcertService(concertRepository, scheduleRepository, confirmedReservationRepository);
    }

    @Test
    void 모든_콘서트를_조회한다() {
        // given
        Concert concert = new Concert(1L, "테스트 공연");
        when(concertRepository.findAll()).thenReturn(List.of(concert));

        // when
        List<Concert> concerts = concertService.listConcerts();

        // then
        assertThat(concerts).hasSize(1);
        assertThat(concerts.get(0).getTitle()).isEqualTo("테스트 공연");
    }

    @Test
    void ID로_콘서트를_조회한다() {
        // given
        Concert concert = new Concert(1L, "테스트 공연");
        when(concertRepository.findById(1L)).thenReturn(Optional.of(concert));

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
        when(scheduleRepository.findByConcertIdAndConcertDate(1L, date)).thenReturn(Optional.empty());

        // when & then
        assertThrows(IllegalArgumentException.class,
                () -> concertService.listAvailableSeats(1L, date));
    }

    @Test
    void 예약된_좌석을_제외하고_남은_좌석을_조회한다() {
        // given
        LocalDate date = LocalDate.now();
        var concert = new ConcertJpaEntity("테스트 공연"); // 최소 생성자 or builder 필요
        var schedule = new ConcertScheduleJpaEntity(concert, date, 5); // seatCount=5

        when(scheduleRepository.findByConcertIdAndConcertDate(1L, date))
                .thenReturn(Optional.of(schedule));
        when(confirmedReservationRepository.findSeatNosByConcertDate(date))
                .thenReturn(List.of(2, 4)); // 2번, 4번 좌석 예약됨

        // when
        List<Integer> available = concertService.listAvailableSeats(1L, date);

        // then
        assertThat(available).containsExactly(1, 3, 5);
    }
}
