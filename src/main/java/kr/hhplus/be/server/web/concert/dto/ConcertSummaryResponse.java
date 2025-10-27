package kr.hhplus.be.server.web.concert.dto;

import java.io.Serializable;

public record ConcertSummaryResponse(
        Long id, String title
) implements Serializable {}
