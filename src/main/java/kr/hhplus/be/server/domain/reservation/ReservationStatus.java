package kr.hhplus.be.server.domain.reservation;

/**
 * 예약 상태를 나타내는 Enum
 */
public enum ReservationStatus {

    TEMPORARY_ASSIGNED("임시배정", "좌석이 임시로 배정된 상태"),
    CONFIRMED("확정", "결제 완료로 예약이 확정된 상태"),
    CANCELLED("취소", "예약이 취소된 상태"),
    EXPIRED("만료", "임시배정 시간이 만료된 상태");

    private final String displayName;
    private final String description;

    ReservationStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    // 상태 전환 가능 여부 체크
    public boolean canTransitionTo(ReservationStatus targetStatus) {
        return switch (this) {
            case TEMPORARY_ASSIGNED -> targetStatus == CONFIRMED ||
                    targetStatus == CANCELLED ||
                    targetStatus == EXPIRED;
            case CONFIRMED -> targetStatus == CANCELLED; // 확정된 예약도 취소 가능
            case CANCELLED, EXPIRED -> false; // 최종 상태
        };
    }

    // 활성 상태 여부
    public boolean isActive() {
        return this == TEMPORARY_ASSIGNED || this == CONFIRMED;
    }

    // 완료된 상태 여부
    public boolean isFinalized() {
        return this == CANCELLED || this == EXPIRED;
    }

    // 결제가 필요한 상태 여부
    public boolean requiresPayment() {
        return this == TEMPORARY_ASSIGNED;
    }
}