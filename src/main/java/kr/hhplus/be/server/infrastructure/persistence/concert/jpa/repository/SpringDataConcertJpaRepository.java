package kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository;

import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SpringDataConcertJpaRepository extends JpaRepository<ConcertJpaEntity, Long> {
    boolean existsByTitle(String title); // 선택
}
