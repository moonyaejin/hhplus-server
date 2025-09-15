package kr.hhplus.be.server.web.concert;

import kr.hhplus.be.server.application.service.ConcertService;
import kr.hhplus.be.server.web.concert.dto.ConcertDto;
import kr.hhplus.be.server.web.concert.dto.ScheduleDto;
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

    // UseCase 대신 Service 직접 주입
    private final ConcertService concertService;

    @GetMapping
    public ResponseEntity<List<ConcertDto>> getAllConcerts() {
        List<ConcertDto> concerts = concertService.getAllConcerts();
        return ResponseEntity.ok(concerts);
    }

    @GetMapping("/dates")
    public ResponseEntity<List<LocalDate>> getAvailableDates(
            @RequestParam(defaultValue = "30") int days) {
        List<LocalDate> dates = concertService.getAvailableDates(days);
        return ResponseEntity.ok(dates);
    }

    @GetMapping("/{concertId}/schedule")
    public ResponseEntity<ScheduleDto> getConcertSchedule(
            @PathVariable Long concertId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        ScheduleDto schedule = concertService.getConcertSchedule(concertId, date);
        return ResponseEntity.ok(schedule);
    }

    @GetMapping("/{concertId}")
    public ResponseEntity<ConcertDto> getConcertDetail(@PathVariable Long concertId) {
        ConcertDto concert = concertService.getConcertDetail(concertId);
        return ResponseEntity.ok(concert);
    }
}