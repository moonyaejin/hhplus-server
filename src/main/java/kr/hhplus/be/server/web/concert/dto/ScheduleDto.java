package kr.hhplus.be.server.web.concert.dto;

import java.time.LocalDate;
import java.util.List;

public record ScheduleDto(
        Long scheduleId,
        Long concertId,
        LocalDate concertDate,
        int totalSeats,
        List<Integer> availableSeats
) {}