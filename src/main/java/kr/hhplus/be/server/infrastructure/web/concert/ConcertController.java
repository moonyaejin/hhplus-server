package kr.hhplus.be.server.infrastructure.web.concert;

import kr.hhplus.be.server.application.usecase.concert.ConcertUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/concerts")
@RequiredArgsConstructor
public class ConcertController {
    private final ConcertUseCase concerts;

    @GetMapping
    public List<Map<String,Object>> list() {
        return concerts.listConcerts().stream()
                .map(c -> Map.of("id", c.getId(), "title", c.getTitle()))
                .toList();
    }

    @GetMapping("/{id}/seats")
    public List<Integer> seats(@PathVariable Long id, @RequestParam String date) {
        return concerts.listAvailableSeats(id, LocalDate.parse(date));
    }
}

