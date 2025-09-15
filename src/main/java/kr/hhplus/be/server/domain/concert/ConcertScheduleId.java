package kr.hhplus.be.server.domain.concert;

public record ConcertScheduleId(Long value) {
    public ConcertScheduleId {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("콘서트 스케줄 ID는 양수여야 합니다");
        }
    }
}