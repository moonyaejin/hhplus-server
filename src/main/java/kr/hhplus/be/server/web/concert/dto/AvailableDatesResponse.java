package kr.hhplus.be.server.web.concert.dto;

import java.time.LocalDate;
import java.util.List;

public record AvailableDatesResponse(
        List<LocalDate> dates
) {}