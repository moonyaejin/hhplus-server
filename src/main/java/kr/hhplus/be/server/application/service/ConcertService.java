package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertScheduleJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertScheduleJpaRepository;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.ConfirmedReservationJpaRepository;
import kr.hhplus.be.server.web.concert.dto.ConcertDto;
import kr.hhplus.be.server.web.concert.dto.ScheduleDto;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConcertService {

    // JPA Repository 직접 의존 (Port 제거)
    private final ConcertJpaRepository concertJpaRepository;
    private final ConcertScheduleJpaRepository scheduleJpaRepository;
    private final ConfirmedReservationJpaRepository confirmedReservationRepository;

    /**
     * 전체 콘서트 목록 조회
     */
    @Cacheable(value = "concerts")
    public List<ConcertDto> getAllConcerts() {
        List<ConcertJpaEntity> entities = concertJpaRepository.findAll();

        return entities.stream()
                .map(entity -> new ConcertDto(
                        entity.getId(),
                        entity.getTitle(),
                        "콘서트 설명"  // description이 없으면 기본값
                ))
                .toList();
    }

    /**
     * 예약 가능한 날짜 목록 조회
     */
    public List<LocalDate> getAvailableDates(int days) {
        // 간단한 구현: 오늘부터 days일간의 날짜 반환
        // 실제로는 DB에서 스케줄이 있는 날짜만 조회해야 함
        return IntStream.range(0, days)
                .mapToObj(i -> LocalDate.now().plusDays(i))
                .toList();
    }

    /**
     * 특정 콘서트의 특정 날짜 스케줄 조회
     */
    @Cacheable(value = "schedule", key = "#concertId + ':' + #date")
    public ScheduleDto getConcertSchedule(Long concertId, LocalDate date) {
        // 스케줄 조회
        ConcertScheduleJpaEntity schedule = scheduleJpaRepository
                .findByConcertIdAndConcertDate(concertId, date)
                .orElseThrow(() -> new RuntimeException("해당 날짜의 콘서트 스케줄을 찾을 수 없습니다"));

        // 확정된 좌석 번호들 조회
        List<Integer> confirmedSeats = confirmedReservationRepository
                .findSeatNosByConcertDate(date);

        // 전체 좌석에서 확정된 좌석 제외한 가용 좌석 계산
        List<Integer> availableSeats = IntStream.rangeClosed(1, schedule.getSeatCount())
                .filter(seatNo -> !confirmedSeats.contains(seatNo))
                .boxed()
                .toList();

        return new ScheduleDto(
                schedule.getId(),
                schedule.getConcert().getId(),
                schedule.getConcertDate(),
                schedule.getSeatCount(),
                availableSeats
        );
    }

    /**
     * 특정 콘서트 상세 조회
     */
    @Cacheable(value = "concertDetail", key = "#concertId")
    public ConcertDto getConcertDetail(Long concertId) {
        ConcertJpaEntity entity = concertJpaRepository.findById(concertId)
                .orElseThrow(() -> new RuntimeException("콘서트를 찾을 수 없습니다"));

        return new ConcertDto(
                entity.getId(),
                entity.getTitle(),
                "상세 설명"
        );
    }

    /**
     * 콘서트 목록 캐시 무효화 (테스트용)
     */
    @CacheEvict(value = "concerts", allEntries = true)
    public void evictConcertCache() {
        // 캐시 무효화만 수행
    }

    /**
     * 특정 스케줄 캐시 무효화 (테스트용)
     */
    @CacheEvict(value = "schedule", key = "#concertId + ':' + #date")
    public void evictScheduleCache(Long concertId, LocalDate date) {
        // 캐시 무효화만 수행
    }

    /**
     * 콘서트 상세 캐시 무효화 (테스트용)
     */
    @CacheEvict(value = "concertDetail", key = "#concertId")
    public void evictConcertDetailCache(Long concertId) {
        // 캐시 무효화만 수행
    }
}