package kr.hhplus.be.server.web.concert.dto;

import java.io.Serializable;

public record ConcertDto(
        Long id,
        String title,
        String description
) implements Serializable {}