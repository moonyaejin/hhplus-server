package kr.hhplus.be.server.application.service;

import kr.hhplus.be.server.application.port.in.SeatQueryUseCase;
import kr.hhplus.be.server.application.port.out.*;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import kr.hhplus.be.server.domain.reservation.SeatIdentifier;
import kr.hhplus.be.server.domain.reservation.SeatNumber;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;  // Map 추가

/**
 * 좌석 조회 애플리케이션 서비스
 * - 여러 포트를 조율하여 좌석 상태 조회
 * - 복잡한 비즈니스 로직을 애플리케이션 계층에서 처리
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeatQueryService implements SeatQueryUseCase {

    private final ConcertSchedulePort concertSchedulePort;
    private final ConfirmedReservationPort confirmedReservationPort;
    private final SeatHoldPort seatHoldPort;

    @Override
    public List<SeatQueryPort.SeatView> getSeatsStatus(Long concertId, LocalDate date) {
        // 1. 콘서트 스케줄 조회
        var schedule = concertSchedulePort.findByConcertIdAndConcertDate(concertId, date)
                .orElseThrow(() -> new IllegalArgumentException("해당 날짜의 콘서트 스케줄을 찾을 수 없습니다"));

        int totalSeats = schedule.getTotalSeats();
        ConcertScheduleId scheduleId = schedule.getId();

        // 2. 확정 예약된 좌석들 조회
        Set<Integer> confirmedSeats = new HashSet<>(
                confirmedReservationPort.findSeatNosByConcertDate(date)
        );

        // 3. 확정되지 않은 좌석들의 SeatIdentifier 목록 생성
        List<SeatIdentifier> nonConfirmedSeats = new ArrayList<>();
        for (int seatNo = 1; seatNo <= totalSeats; seatNo++) {
            if (!confirmedSeats.contains(seatNo)) {
                nonConfirmedSeats.add(new SeatIdentifier(scheduleId, new SeatNumber(seatNo)));
            }
        }

        // 4. Redis에서 한번에 조회 (파이프라인 활용!)
        Map<SeatIdentifier, SeatHoldStatus> holdStatusMap =
                seatHoldPort.getHoldStatusBulk(nonConfirmedSeats);

        // 5. 결과 조립
        List<SeatQueryPort.SeatView> seatViews = new ArrayList<>(totalSeats);
        LocalDateTime now = LocalDateTime.now();

        for (int seatNo = 1; seatNo <= totalSeats; seatNo++) {
            if (confirmedSeats.contains(seatNo)) {
                // 확정된 좌석
                seatViews.add(new SeatQueryPort.SeatView(
                        seatNo,
                        SeatQueryPort.SeatStatus.CONFIRMED,
                        null
                ));
            } else {
                // Redis에서 조회한 결과 확인
                SeatIdentifier seatId = new SeatIdentifier(scheduleId, new SeatNumber(seatNo));
                SeatHoldStatus holdStatus = holdStatusMap.get(seatId);

                if (holdStatus != null && !holdStatus.isExpired(now)) {
                    // 점유된 좌석
                    long remainingSeconds = holdStatus.remainingTime(now).getSeconds();
                    seatViews.add(new SeatQueryPort.SeatView(
                            seatNo,
                            SeatQueryPort.SeatStatus.HELD,
                            remainingSeconds
                    ));
                } else {
                    // 예약 가능한 좌석
                    seatViews.add(new SeatQueryPort.SeatView(
                            seatNo,
                            SeatQueryPort.SeatStatus.FREE,
                            null
                    ));
                }
            }
        }

        return seatViews;
    }

    @Override
    public List<LocalDate> getAvailableDates(int days) {
        // SeatQueryUseCase 인터페이스의 메서드 구현
        return concertSchedulePort.findAvailableDates(days);
    }
}