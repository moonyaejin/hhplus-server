package kr.hhplus.be.server.application.port.out;

import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.reservation.SeatIdentifier;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 좌석 임시 점유 관리 포트
 * - 도메인 중심의 인터페이스
 * - 기술적 세부사항(Redis TTL 등) 숨김
 * - Value Object 활용
 */
public interface SeatHoldPort {

    /**
     * 좌석 임시 점유 시도
     *
     * @param seatIdentifier 좌석 식별자
     * @param userId 사용자 ID
     * @param holdDuration 점유 지속 시간
     * @return 점유 성공 여부
     */
    boolean tryHold(SeatIdentifier seatIdentifier, UserId userId, Duration holdDuration);

    /**
     * 특정 사용자가 좌석을 점유하고 있는지 확인
     *
     * @param seatIdentifier 좌석 식별자
     * @param userId 사용자 ID
     * @return 점유 중인지 여부
     */
    boolean isHeldBy(SeatIdentifier seatIdentifier, UserId userId);

    /**
     * 좌석 점유 해제
     *
     * @param seatIdentifier 좌석 식별자
     */
    void release(SeatIdentifier seatIdentifier);

    /**
     * 좌석 점유 상태 조회
     *
     * @param seatIdentifier 좌석 식별자
     * @return 점유 정보 (점유되지 않은 경우 null)
     */
    SeatHoldStatus getHoldStatus(SeatIdentifier seatIdentifier);

    /**
     * 여러 좌석의 점유 상태를 한번에 조회 (파이프라인용)
     *
     * @param seatIdentifiers 조회할 좌석 목록
     * @return 좌석별 점유 상태 맵 (점유되지 않은 좌석은 맵에 없음)
     */
    Map<SeatIdentifier, SeatHoldStatus> getHoldStatusBulk(List<SeatIdentifier> seatIdentifiers);
}