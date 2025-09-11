package kr.hhplus.be.server.domain.user.model;

import kr.hhplus.be.server.domain.common.UserId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class User {
    private final UserId id;
    private String name;
    private UserStatus status;
    private UserTier tier;
    private final Instant createdAt;
    private Instant lastLoginAt;
    private int totalReservationCount;
    private long totalSpentAmount;

    // 도메인 이벤트 (Optional)
    private final List<Object> domainEvents = new ArrayList<>();

    public User(UserId id, String name, Instant createdAt) {
        validateName(name);

        this.id = id;
        this.name = name;
        this.status = UserStatus.ACTIVE;
        this.tier = UserTier.BRONZE;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.totalReservationCount = 0;
        this.totalSpentAmount = 0L;
    }

    // ========== 비즈니스 메서드 ==========

    /**
     * 예약 완료 시 사용자 통계 업데이트
     */
    public void recordReservation(long amount) {
        this.totalReservationCount++;
        this.totalSpentAmount += amount;
        updateTier();
    }

    /**
     * 로그인 기록
     */
    public void recordLogin() {
        this.lastLoginAt = Instant.now();
    }

    /**
     * 사용자 정보 변경
     */
    public void updateProfile(String name) {
        validateName(name);
        this.name = name;
    }

    /**
     * 사용자 비활성화
     */
    public void deactivate() {
        if (this.status == UserStatus.INACTIVE) {
            throw new IllegalStateException("이미 비활성화된 사용자입니다");
        }
        this.status = UserStatus.INACTIVE;
    }

    /**
     * 사용자 활성화
     */
    public void activate() {
        if (this.status == UserStatus.ACTIVE) {
            throw new IllegalStateException("이미 활성화된 사용자입니다");
        }
        this.status = UserStatus.ACTIVE;
    }

    /**
     * 등급 자동 업데이트
     */
    private void updateTier() {
        if (totalSpentAmount >= 1_000_000L) {
            this.tier = UserTier.PLATINUM;
        } else if (totalSpentAmount >= 500_000L) {
            this.tier = UserTier.GOLD;
        } else if (totalSpentAmount >= 200_000L) {
            this.tier = UserTier.SILVER;
        }
    }

    /**
     * VIP 여부 확인
     */
    public boolean isVip() {
        return tier == UserTier.GOLD || tier == UserTier.PLATINUM;
    }

    /**
     * 예약 가능 여부 확인
     */
    public boolean canMakeReservation() {
        return status == UserStatus.ACTIVE;
    }

    // ========== Validation ==========

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("이름은 필수입니다");
        }
        if (name.length() > 50) {
            throw new IllegalArgumentException("이름은 50자를 초과할 수 없습니다");
        }
    }

    // ========== Getters ==========
    public UserId id() { return id; }
    public String name() { return name; }
    public UserStatus status() { return status; }
    public UserTier tier() { return tier; }
    public Instant createdAt() { return createdAt; }
    public Instant lastLoginAt() { return lastLoginAt; }
    public int totalReservationCount() { return totalReservationCount; }
    public long totalSpentAmount() { return totalSpentAmount; }

    // ========== Enums ==========

    public enum UserStatus {
        ACTIVE("활성"),
        INACTIVE("비활성"),
        SUSPENDED("정지");

        private final String description;

        UserStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public enum UserTier {
        BRONZE("브론즈", 0),
        SILVER("실버", 5),
        GOLD("골드", 10),
        PLATINUM("플래티넘", 15);

        private final String displayName;
        private final int discountRate;

        UserTier(String displayName, int discountRate) {
            this.displayName = displayName;
            this.discountRate = discountRate;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getDiscountRate() {
            return discountRate;
        }
    }
}