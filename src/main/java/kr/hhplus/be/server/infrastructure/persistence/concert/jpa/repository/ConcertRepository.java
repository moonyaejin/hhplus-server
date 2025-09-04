package kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository;

import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConcertRepository extends JpaRepository<ConcertJpaEntity, Long> {
    boolean existsByTitle(String title);
}
