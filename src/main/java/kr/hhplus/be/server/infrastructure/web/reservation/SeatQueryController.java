package kr.hhplus.be.server.infrastructure.web.reservation;

import kr.hhplus.be.server.application.usecase.concert.ConcertUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/reservations")
@RequiredArgsConstructor
public class SeatQueryController {
    private final ConcertUseCase concerts;

    @GetMapping
    public List<ConcertResponse> list() {
        return concerts.listConcerts().stream()
                .map(c -> new ConcertResponse(c.getId(), c.getTitle()))
                .toList();
    }

    @GetMapping("/{id}/seats")
    public List<Integer> seats(@PathVariable Long id, @RequestParam String date) {
        return concerts.listAvailableSeats(id, LocalDate.parse(date));
    }

    public record ConcertResponse(Long id, String title) {}
}