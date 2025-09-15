package kr.hhplus.be.server;

import kr.hhplus.be.server.application.service.ConcertService;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertScheduleJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertScheduleJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.ConfirmedReservationJpaRepository;
import kr.hhplus.be.server.web.concert.dto.ConcertDto;
import kr.hhplus.be.server.web.concert.dto.ScheduleDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConcertServiceTest {

    @Mock
    private ConcertJpaRepository concertJpaRepository;

    @Mock
    private ConcertScheduleJpaRepository scheduleJpaRepository;

    @Mock
    private ConfirmedReservationJpaRepository confirmedReservationRepository;

    private ConcertService concertService;

    @BeforeEach
    void setUp() {
        concertService = new ConcertService(
                concertJpaRepository,
                scheduleJpaRepository,
                confirmedReservationRepository
        );
    }

    @Test
    void 모든_콘서트를_조회한다() {
        // given
        ConcertJpaEntity entity = new ConcertJpaEntity("테스트 공연");
        // Reflection으로 ID 설정 (JPA Entity는 보통 ID가 자동 생성)
        setFieldValue(entity, "id", 1L);

        when(concertJpaRepository.findAll()).thenReturn(List.of(entity));

        // when
        List<ConcertDto> concerts = concertService.getAllConcerts();

        // then
        assertThat(concerts).hasSize(1);
        assertThat(concerts.get(0).title()).isEqualTo("테스트 공연");
        assertThat(concerts.get(0).id()).isEqualTo(1L);
    }

    @Test
    void ID로_콘서트_상세를_조회한다() {
        // given
        ConcertJpaEntity entity = new ConcertJpaEntity("테스트 공연");
        setFieldValue(entity, "id", 1L);

        when(concertJpaRepository.findById(1L)).thenReturn(Optional.of(entity));

        // when
        ConcertDto result = concertService.getConcertDetail(1L);

        // then
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.title()).isEqualTo("테스트 공연");
    }

    @Test
    void 존재하지_않는_콘서트_조회시_예외가_발생한다() {
        // given
        when(concertJpaRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThrows(RuntimeException.class,
                () -> concertService.getConcertDetail(999L),
                "콘서트를 찾을 수 없습니다");
    }

    @Test
    void 예약_가능한_날짜_목록을_조회한다() {
        // when
        List<LocalDate> dates = concertService.getAvailableDates(3);

        // then
        assertThat(dates).hasSize(3);
        assertThat(dates.get(0)).isEqualTo(LocalDate.now());
        assertThat(dates.get(1)).isEqualTo(LocalDate.now().plusDays(1));
        assertThat(dates.get(2)).isEqualTo(LocalDate.now().plusDays(2));
    }

    @Test
    void 콘서트_스케줄과_가용_좌석을_조회한다() {
        // given
        LocalDate date = LocalDate.now();

        // Concert Entity 생성
        ConcertJpaEntity concertEntity = new ConcertJpaEntity("테스트 공연");
        setFieldValue(concertEntity, "id", 1L);

        // Schedule Entity 생성
        ConcertScheduleJpaEntity scheduleEntity = new ConcertScheduleJpaEntity(
                concertEntity, date, 5
        );
        setFieldValue(scheduleEntity, "id", 100L);

        when(scheduleJpaRepository.findByConcertIdAndConcertDate(1L, date))
                .thenReturn(Optional.of(scheduleEntity));

        // 2번, 4번 좌석이 예약됨
        when(confirmedReservationRepository.findSeatNosByConcertDate(date))
                .thenReturn(List.of(2, 4));

        // when
        ScheduleDto schedule = concertService.getConcertSchedule(1L, date);

        // then
        assertThat(schedule.scheduleId()).isEqualTo(100L);
        assertThat(schedule.concertId()).isEqualTo(1L);
        assertThat(schedule.concertDate()).isEqualTo(date);
        assertThat(schedule.totalSeats()).isEqualTo(5);
        assertThat(schedule.availableSeats()).containsExactly(1, 3, 5);
    }

    @Test
    void 존재하지_않는_스케줄_조회시_예외가_발생한다() {
        // given
        LocalDate date = LocalDate.now();
        when(scheduleJpaRepository.findByConcertIdAndConcertDate(1L, date))
                .thenReturn(Optional.empty());

        // when & then
        assertThrows(RuntimeException.class,
                () -> concertService.getConcertSchedule(1L, date),
                "해당 날짜의 콘서트 스케줄을 찾을 수 없습니다");
    }

    // Reflection 헬퍼 메서드 (테스트용)
    private void setFieldValue(Object obj, String fieldName, Object value) {
        try {
            var field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }
}