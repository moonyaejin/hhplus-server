package kr.hhplus.be.server.application.port.out;

import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConcertPort extends JpaRepository<ConcertJpaEntity, Long> {
    boolean existsByTitle(String title);
}
