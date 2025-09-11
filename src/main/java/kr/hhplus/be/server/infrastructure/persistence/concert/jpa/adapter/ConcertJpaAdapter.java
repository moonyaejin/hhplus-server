package kr.hhplus.be.server.infrastructure.persistence.concert.jpa.adapter;

import kr.hhplus.be.server.application.port.out.ConcertPort;
import kr.hhplus.be.server.domain.concert.Concert;
import kr.hhplus.be.server.domain.concert.ConcertId;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity.ConcertJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.concert.jpa.repository.ConcertJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ConcertJpaAdapter implements ConcertPort {

    private final ConcertJpaRepository repo;

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

    // ConcertId를 받는 메서드는 private helper로 제공 (필요한 경우)
    private Optional<Concert> findByConcertId(ConcertId id) {
        return findById(id.value());
    }

    private Concert toDomain(ConcertJpaEntity e) {
        return new Concert(
                new ConcertId(e.getId()),
                e.getTitle(),
                null // description이 없으므로 null
        );
    }
}