package kr.hhplus.be.server.domain.concert;

public record ConcertId(Long value) {
    public ConcertId {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("콘서트 ID는 양수여야 합니다");
        }
    }
}