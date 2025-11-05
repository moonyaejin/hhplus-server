package kr.hhplus.be.server.web.ranking;

import kr.hhplus.be.server.application.port.in.RankingUseCase;
import kr.hhplus.be.server.application.service.ConcertService;
import kr.hhplus.be.server.application.port.in.RankingUseCase.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rankings")
@RequiredArgsConstructor
public class RankingController {
    private final RankingUseCase rankingUseCase;

    /**
     * 빠른 판매 랭킹 조회
     * 판매 속도 기준, 매진 여부 포함
     */
    @GetMapping("/fast-selling")
    public ResponseEntity<List<ConcertRankingDto>> getFastSelling(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(rankingUseCase.getFastSellingRanking(limit));
    }
}
