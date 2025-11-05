package kr.hhplus.be.server.application.port.out;

import java.util.Map;
import java.util.Set;

public interface RankingPort {
    // 통계 저장
    void saveStats(String scheduleId, Map<String, String> stats);

    // 통계 조회
    Map<String, String> getStats(String scheduleId);

    // 판매 수량 증가
    long incrementSoldCount(String scheduleId, int increment);

    // 첫 판매 시간 설정
    boolean setStartTimeIfAbsent(String scheduleId, long startTime);

    // 판매 속도 랭킹 업데이트
    void updateVelocityRanking(String scheduleId, double score);

    // 매진 랭킹 업데이트
    void updateSoldOutRanking(String scheduleId, long seconds);

    // 판매 속도 기준 상위 랭킹 조회
    Set<String> getTopByVelocity(int limit);

    // 매진 속도 기준 상위 랭킹 조회
    Set<String> getTopBySoldOut(int limit);
}