package kr.hhplus.be.server.reservation.adapter.web;

import kr.hhplus.be.server.reservation.query.SeatQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/reservations")
@RequiredArgsConstructor
public class SeatQueryController {
    private final SeatQueryService seatQuery;

    // 예약 가능 날짜
    @GetMapping("/dates")
    public List<LocalDate> availableDates(@RequestParam(defaultValue = "7") int days) {
        return seatQuery.availableDates(days);
    }

    @GetMapping("/{dates}seats")
    public List<SeatQueryService.SeatView> seats (
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return seatQuery.seats(date);
    }
}
