package kr.hhplus.be.server.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 확정된 예약 관리 포트
 */
public interface ConfirmedReservationPort {

    /**
     * 특정 좌석이 이미 확정 예약되었는지 확인
     */
    boolean exists(LocalDate date, int seatNo);

    /**
     * 확정 예약 저장
     * @return 생성된 예약 ID
     */
    long insert(LocalDate date, int seatNo, String userId, long price, Instant paidAt);

    /**
     * 특정 날짜의 확정된 좌석 번호들 조회
     */
    List<Integer> findSeatNosByConcertDate(LocalDate date);
}