package kr.hhplus.be.server.application.port.out;

import kr.hhplus.be.server.domain.concert.Concert;

import java.util.List;
import java.util.Optional;

public interface ConcertPort {
    Optional<Concert> findById(Long id);
    List<Concert> findAll();
    Concert save(Concert concert);
}