package kr.hhplus.be.server.infrastructure.persistence.concert.jpa.adapter;

import kr.hhplus.be.server.application.port.out.ConcertSchedulePort;
import kr.hhplus.be.server.domain.concert.ConcertId;
import kr.hhplus.be.server.domain.concert.ConcertSchedule;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertScheduleJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertScheduleJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ConcertScheduleJpaAdapter implements ConcertSchedulePort {

    private final ConcertScheduleJpaRepository repository;

    @Override
    public Optional<ConcertSchedule> findById(ConcertScheduleId id) {
        return repository.findById(id.value())
                .map(this::toDomain);
    }

    @Override
    public Optional<ConcertSchedule> findByConcertIdAndConcertDate(Long concertId, LocalDate concertDate) {
        return repository.findByConcertIdAndConcertDate(concertId, concertDate)
                .map(this::toDomain);
    }

    @Override
    public List<ConcertSchedule> findByConcertId(Long concertId) {
        return repository.findByConcertId(concertId)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<LocalDate> findAvailableDates(int days) {
        // 간단한 구현 - 오늘부터 days만큼의 날짜 반환
        // 실제로는 DB에서 예약 가능한 날짜만 조회해야 합니다
        return java.util.stream.IntStream.range(0, days)
                .mapToObj(i -> LocalDate.now().plusDays(i))
                .toList();
    }

    /**
     * 여러 스케줄을 한 번에 조회
     */
    @Override
    public Map<Long, ConcertSchedule> findAllByIds(List<Long> ids) {
        return repository.findAllById(ids)
                .stream()
                .collect(Collectors.toMap(
                        ConcertScheduleJpaEntity::getId,
                        this::toDomain
                ));
    }

    @Override
    public ConcertSchedule save(ConcertSchedule schedule) {
        // 도메인 → JPA 엔티티 변환
        ConcertScheduleJpaEntity entity;

        if (schedule.getId() != null && schedule.getId().value() != null) {
            // 기존 엔티티 업데이트
            entity = repository.findById(schedule.getId().value())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "존재하지 않는 스케줄입니다: " + schedule.getId().value()));

            // 필요한 필드 업데이트 (예: 좌석수 변경 등)
        } else {
            // 새 엔티티 생성
            ConcertJpaEntity concertEntity = repository.findById(schedule.getConcertId().value())
                    .map(ConcertScheduleJpaEntity::getConcert)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "존재하지 않는 콘서트입니다: " + schedule.getConcertId().value()));

            entity = new ConcertScheduleJpaEntity(
                    concertEntity,
                    schedule.getConcertDate(),
                    schedule.getTotalSeats()
            );
        }

        ConcertScheduleJpaEntity savedEntity = repository.save(entity);
        return toDomain(savedEntity);
    }

    private ConcertSchedule toDomain(ConcertScheduleJpaEntity entity) {
        return new ConcertSchedule(
                new ConcertScheduleId(entity.getId()),
                new ConcertId(entity.getConcert().getId()),
                entity.getConcertDate(),
                entity.getSeatCount()
        );
    }
}