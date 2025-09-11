package kr.hhplus.be.server.web.concert;

import kr.hhplus.be.server.application.port.in.ConcertUseCase;
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
    public ResponseEntity<List<ConcertUseCase.ConcertInfo>> getAllConcerts() {
        var result = concertUseCase.getAllConcerts();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/dates")
    public ResponseEntity<List<LocalDate>> getAvailableDates(@RequestParam(defaultValue = "30") int days) {
        var result = concertUseCase.getAvailableDates(days);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{concertId}/schedule")
    public ResponseEntity<ConcertUseCase.ScheduleInfo> getConcertSchedule(
            @PathVariable Long concertId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        var result = concertUseCase.getConcertSchedule(concertId, date);
        return ResponseEntity.ok(result);
    }
}