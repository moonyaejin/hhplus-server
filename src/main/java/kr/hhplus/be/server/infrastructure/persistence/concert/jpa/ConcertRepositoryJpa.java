package kr.hhplus.be.server.infrastructure.persistence.concert.jpa;

import kr.hhplus.be.server.domain.concert.Concert;
import kr.hhplus.be.server.domain.concert.ConcertRepository;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.SpringDataConcertJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ConcertRepositoryJpa implements ConcertRepository {

    private final SpringDataConcertJpaRepository repo; // extends JpaRepository<ConcertJpaEntity, Long>

    @Override
    public Optional<Concert> findById(Long id) {
        return repo.findById(id).map(this::toDomain);
    }

    @Override
    public List<Concert> findAll() {
        return repo.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public Concert save(Concert concert) {
        ConcertJpaEntity entity = new ConcertJpaEntity(concert.getTitle());
        ConcertJpaEntity saved = repo.save(entity);
        return toDomain(saved);
    }

    private Concert toDomain(ConcertJpaEntity e) {
        return new Concert(e.getId(), e.getTitle());
    }
}
