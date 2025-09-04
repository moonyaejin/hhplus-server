package kr.hhplus.be.server.domain.concert;

import java.util.List;
import java.util.Optional;

public interface ConcertRepository {
    Optional<Concert> findById(Long id);
    List<Concert> findAll();
    Concert save(Concert concert);
}
