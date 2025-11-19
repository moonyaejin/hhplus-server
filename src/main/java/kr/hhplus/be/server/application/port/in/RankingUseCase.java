package kr.hhplus.be.server.application.port.in;

import java.io.Serializable;
import java.util.List;

public interface RankingUseCase {

    // 예약 추적
    void trackReservation(Long scheduleId, int seatCount);

    // 예약 취소 시 랭킹 차감
    void decrementReservation(Long scheduleId, int seatCount);

    // 빠른 판매 랭킹 조회
    List<ConcertRankingDto> getFastSellingRanking(int limit);

    // 통합 랭킹 DTO
    record ConcertRankingDto(
            int rank,
            Long scheduleId,
            String concertName,
            int soldCount,
            double velocityPerMinute,
            boolean isSoldOut,
            Integer soldOutSeconds
    ) implements Serializable {}
}