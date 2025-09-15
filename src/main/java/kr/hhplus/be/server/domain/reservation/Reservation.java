package kr.hhplus.be.server.domain.reservation;

import kr.hhplus.be.server.domain.common.Money;
import kr.hhplus.be.server.domain.common.UserId;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 예약 애그리게이트 루트
 * - 예약의 전체 생명주기 관리
 * - 불변성과 일관성 보장
 * - 도메인 이벤트 발행 준비
 */
public class Reservation {

    private static final int TEMPORARY_ASSIGN_MINUTES = 5;

    private final ReservationId id;
    private final UserId userId;
    private final SeatIdentifier seatIdentifier;
    private final Money price;
    private final LocalDateTime temporaryAssignedAt;

    private ReservationStatus status;
    private LocalDateTime confirmedAt;
    private long version;

    // Private 생성자 - 외부에서 직접 생성 불가
    private Reservation(ReservationId id, UserId userId, SeatIdentifier seatIdentifier,
                        Money price, ReservationStatus status, LocalDateTime temporaryAssignedAt,
                        LocalDateTime confirmedAt, long version) {
        this.id = Objects.requireNonNull(id, "예약 ID는 필수입니다");
        this.userId = Objects.requireNonNull(userId, "사용자 ID는 필수입니다");
        this.seatIdentifier = Objects.requireNonNull(seatIdentifier, "좌석 식별자는 필수입니다");
        this.price = Objects.requireNonNull(price, "가격은 필수입니다");
        this.status = Objects.requireNonNull(status, "상태는 필수입니다");
        this.temporaryAssignedAt = Objects.requireNonNull(temporaryAssignedAt, "임시배정시간은 필수입니다");
        this.confirmedAt = confirmedAt;
        this.version = version;
    }

    // === 팩토리 메서드들 ===

    /**
     * 임시 배정으로 새 예약 생성
     */
    public static Reservation temporaryAssign(UserId userId, SeatIdentifier seatIdentifier,
                                              Money price, LocalDateTime assignedAt) {
        validateTemporaryAssignInputs(userId, seatIdentifier, price, assignedAt);

        return new Reservation(
                ReservationId.generate(),
                userId,
                seatIdentifier,
                price,
                ReservationStatus.TEMPORARY_ASSIGNED,
                assignedAt,
                null,
                0L
        );
        // 도메인 이벤트 발행 (향후 추가)
        // addDomainEvent(new ReservationTemporarilyAssignedEvent(...))
    }

    /**
     * 기존 예약 복원 (Repository에서 사용)
     */
    public static Reservation restore(ReservationId id, UserId userId, SeatIdentifier seatIdentifier,
                                      Money price, ReservationStatus status, LocalDateTime temporaryAssignedAt,
                                      LocalDateTime confirmedAt, long version) {
        return new Reservation(id, userId, seatIdentifier, price, status,
                temporaryAssignedAt, confirmedAt, version);
    }

    private static void validateTemporaryAssignInputs(UserId userId, SeatIdentifier seatIdentifier,
                                                      Money price, LocalDateTime assignedAt) {
        Objects.requireNonNull(userId, "사용자 ID는 필수입니다");
        Objects.requireNonNull(seatIdentifier, "좌석 식별자는 필수입니다");
        Objects.requireNonNull(price, "가격은 필수입니다");
        Objects.requireNonNull(assignedAt, "배정시간은 필수입니다");

        if (!price.isPositive()) {
            throw new IllegalArgumentException("가격은 0보다 커야 합니다");
        }

        if (assignedAt.isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new IllegalArgumentException("배정 시간이 미래 시간일 수 없습니다");
        }
    }

    // === 비즈니스 메서드들 ===

    /**
     * 예약 확정
     */
    public void confirm(LocalDateTime confirmedAt) {
        validateConfirmation(confirmedAt);

        this.status = ReservationStatus.CONFIRMED;
        this.confirmedAt = confirmedAt;

        // 도메인 이벤트 발행 (향후 추가)
        // addDomainEvent(new ReservationConfirmedEvent(...))
    }

    /**
     * 예약 취소
     */
    public void cancel(LocalDateTime cancelledAt) {
        if (!status.canTransitionTo(ReservationStatus.CANCELLED)) {
            throw new IllegalStateException(
                    String.format("현재 상태[%s]에서는 취소할 수 없습니다", status.getDisplayName())
            );
        }

        this.status = ReservationStatus.CANCELLED;

        // 도메인 이벤트 발행 (향후 추가)
        // addDomainEvent(new ReservationCancelledEvent(...))
    }

    /**
     * 예약 만료 처리
     */
    public void expire(LocalDateTime expiredAt) {
        if (status != ReservationStatus.TEMPORARY_ASSIGNED) {
            throw new IllegalStateException("임시배정 상태가 아닌 예약은 만료처리할 수 없습니다");
        }

        this.status = ReservationStatus.EXPIRED;

        // 도메인 이벤트 발행 (향후 추가)
        // addDomainEvent(new ReservationExpiredEvent(...))
    }

    // === 조회 메서드들 ===

    /**
     * 만료 여부 확인
     */
    public boolean isExpired(LocalDateTime currentTime) {
        return status == ReservationStatus.TEMPORARY_ASSIGNED &&
                currentTime.isAfter(getExpirationTime());
    }

    /**
     * 만료 시간 계산
     */
    public LocalDateTime getExpirationTime() {
        return temporaryAssignedAt.plusMinutes(TEMPORARY_ASSIGN_MINUTES);
    }

    /**
     * 확정 가능 여부
     */
    public boolean canConfirm(LocalDateTime currentTime) {
        return status == ReservationStatus.TEMPORARY_ASSIGNED && !isExpired(currentTime);
    }

    /**
     * 결제 필요 여부
     */
    public boolean requiresPayment() {
        return status.requiresPayment();
    }

    // === Getters ===
    public ReservationId getId() { return id; }
    public UserId getUserId() { return userId; }
    public SeatIdentifier getSeatIdentifier() { return seatIdentifier; }
    public Money getPrice() { return price; }
    public ReservationStatus getStatus() { return status; }
    public LocalDateTime getTemporaryAssignedAt() { return temporaryAssignedAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public long getVersion() { return version; }

    // === Private 메서드들 ===

    private void validateConfirmation(LocalDateTime confirmedAt) {
        if (!status.canTransitionTo(ReservationStatus.CONFIRMED)) {
            throw new IllegalStateException(
                    String.format("현재 상태[%s]에서는 확정할 수 없습니다", status.getDisplayName())
            );
        }

        if (isExpired(confirmedAt)) {
            throw new IllegalStateException("만료된 예약은 확정할 수 없습니다");
        }

        if (confirmedAt.isBefore(temporaryAssignedAt)) {
            throw new IllegalStateException("확정 시간이 배정 시간보다 이전일 수 없습니다");
        }
    }

    // === Object methods ===

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Reservation that = (Reservation) obj;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format("Reservation{id=%s, userId=%s, seat=%s, status=%s}",
                id.value(), userId.value(), seatIdentifier.toDisplayString(), status);
    }
}