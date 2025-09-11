package kr.hhplus.be.server.application.port.out;

import kr.hhplus.be.server.domain.concert.ConcertSchedule;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ConcertSchedulePort {

    // ID로 스케줄 조회 (ReservationService에서 필요)
    Optional<ConcertSchedule> findById(ConcertScheduleId id);

    // 특정 콘서트의 특정 날짜 스케줄 조회
    Optional<ConcertSchedule> findByConcertIdAndConcertDate(Long concertId, LocalDate concertDate);

    // 콘서트의 모든 스케줄 조회
    List<ConcertSchedule> findByConcertId(Long concertId);

    // 사용 가능한 날짜 목록 조회
    List<LocalDate> findAvailableDates(int days);

    // 스케줄 저장 (필요시)
    ConcertSchedule save(ConcertSchedule schedule);
}