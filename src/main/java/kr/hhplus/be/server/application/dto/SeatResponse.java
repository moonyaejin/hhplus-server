package kr.hhplus.be.server.application.dto;

public record SeatResponse(
        int seatNo,
        String status,
        Long remainSec
) {}