package kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository;

import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertScheduleJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ConcertScheduleJpaRepository extends JpaRepository<ConcertScheduleJpaEntity, Long> {
    Optional<ConcertScheduleJpaEntity> findByConcertIdAndConcertDate(Long concertId, LocalDate date);
    List<ConcertScheduleJpaEntity> findByConcertId(Long concertId);  // 이 줄 추가
}
