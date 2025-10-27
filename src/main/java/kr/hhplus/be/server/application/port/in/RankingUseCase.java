package kr.hhplus.be.server.application.port.in;

import java.util.List;

public interface RankingUseCase {

    // 판매 추적
    void trackReservation(Long scheduleId, int seatCount);

    // 랭킹 조회
    List<FastSellingDto> getFastSellingRanking(int limit);
    List<SoldOutRankingDto> getFastestSoldOutRanking(int limit);

    // DTO는 여기 내부 클래스로 정의해도 됨
    record FastSellingDto(
            int rank,
            Long scheduleId,
            String concertName,
            double velocityPerMinute
    ) {}

    record SoldOutRankingDto(
            int rank,
            Long scheduleId,
            String concertName,
            int soldOutSeconds,
            String formattedTime
    ) {}
}