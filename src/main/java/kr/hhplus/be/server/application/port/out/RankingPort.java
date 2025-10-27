package kr.hhplus.be.server.application.port.out;

import java.util.Map;
import java.util.Set;

public interface RankingPort {
    // Redis 연산 추상화
    void saveStats(String scheduleId, Map<String, String> stats);
    Map<String, String> getStats(String scheduleId);
    void updateVelocityRanking(String scheduleId, double score);
    void updateSoldOutRanking(String scheduleId, long seconds);
    Set<String> getTopByVelocity(int limit);
    Set<String> getTopBySoldOut(int limit);
}