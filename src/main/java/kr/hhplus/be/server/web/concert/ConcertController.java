package kr.hhplus.be.server.web.concert;

import kr.hhplus.be.server.application.port.in.ConcertUseCase;
import kr.hhplus.be.server.application.dto.concert.ConcertSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/concerts")
@RequiredArgsConstructor
public class ConcertController {

    private final ConcertUseCase concertUseCase;

    @GetMapping
    public ResponseEntity<List<ConcertSummaryResponse>> list() {
        var result = concertUseCase.listConcerts().stream()
                .map(c -> new ConcertSummaryResponse(c.getId(), c.getTitle()))
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/seats")
    public ResponseEntity<List<Integer>> seats(
            @PathVariable Long id,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(concertUseCase.listAvailableSeats(id, date));
    }
}
