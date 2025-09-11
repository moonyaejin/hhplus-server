package kr.hhplus.be.server.application.port.out;

import kr.hhplus.be.server.domain.common.UserId;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 좌석 점유 상태 Value Object
 * - 좌석 점유 정보를 캡슐화
 * - 불변 객체로 설계
 * - 점유 상태 관련 비즈니스 로직 포함
 */
public class SeatHoldStatus {

    private final UserId holderId;
    private final LocalDateTime heldAt;
    private final LocalDateTime expiresAt;

    public SeatHoldStatus(UserId holderId, LocalDateTime heldAt, LocalDateTime expiresAt) {
        this.holderId = Objects.requireNonNull(holderId, "점유자 ID는 필수입니다");
        this.heldAt = Objects.requireNonNull(heldAt, "점유 시작 시간은 필수입니다");
        this.expiresAt = Objects.requireNonNull(expiresAt, "점유 만료 시간은 필수입니다");

        if (expiresAt.isBefore(heldAt)) {
            throw new IllegalArgumentException("만료 시간이 점유 시작 시간보다 이전일 수 없습니다");
        }
    }

    /**
     * 점유가 만료되었는지 확인
     *
     * @param currentTime 현재 시간
     * @return 만료 여부
     */
    public boolean isExpired(LocalDateTime currentTime) {
        return currentTime.isAfter(expiresAt);
    }

    /**
     * 남은 점유 시간 계산
     *
     * @param currentTime 현재 시간
     * @return 남은 시간 (만료된 경우 Duration.ZERO)
     */
    public Duration remainingTime(LocalDateTime currentTime) {
        if (isExpired(currentTime)) {
            return Duration.ZERO;
        } else {
            return Duration.between(currentTime, expiresAt);
        }
    }

    /**
     * 점유 지속 시간 계산
     *
     * @return 전체 점유 지속 시간
     */
    public Duration holdDuration() {
        return Duration.between(heldAt, expiresAt);
    }

    /**
     * 특정 사용자가 점유자인지 확인
     *
     * @param userId 확인할 사용자 ID
     * @return 점유자 여부
     */
    public boolean isHeldBy(UserId userId) {
        return Objects.equals(this.holderId, userId);
    }

    // === Getters ===

    public UserId getHolderId() {
        return holderId;
    }

    public LocalDateTime getHeldAt() {
        return heldAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    // === Object Methods ===

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        SeatHoldStatus that = (SeatHoldStatus) obj;
        return Objects.equals(holderId, that.holderId) &&
                Objects.equals(heldAt, that.heldAt) &&
                Objects.equals(expiresAt, that.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(holderId, heldAt, expiresAt);
    }

    @Override
    public String toString() {
        return String.format("SeatHoldStatus{holderId=%s, heldAt=%s, expiresAt=%s}",
                holderId, heldAt, expiresAt);
    }
}