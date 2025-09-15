package kr.hhplus.be.server.domain.common.exception;

/**
 * 콘서트 스케줄을 찾을 수 없을 때 발생하는 예외
 * - 전체 시스템에서 공통으로 사용
 * - 도메인 계층과 애플리케이션 계층에서 모두 사용 가능
 */
public class ConcertScheduleNotFoundException extends RuntimeException {

    public ConcertScheduleNotFoundException(String message) {
        super(message);
    }

    public ConcertScheduleNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    // 편의 팩토리 메서드들
    public static ConcertScheduleNotFoundException withId(Long scheduleId) {
        return new ConcertScheduleNotFoundException(
                String.format("콘서트 스케줄을 찾을 수 없습니다. ID: %d", scheduleId)
        );
    }

    public static ConcertScheduleNotFoundException withConcertAndDate(Long concertId, String date) {
        return new ConcertScheduleNotFoundException(
                String.format("콘서트 스케줄을 찾을 수 없습니다. 콘서트 ID: %d, 날짜: %s", concertId, date)
        );
    }
}