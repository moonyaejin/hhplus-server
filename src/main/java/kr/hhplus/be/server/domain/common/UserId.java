package kr.hhplus.be.server.domain.common;

// 기존 다른 패키지의 UserId들을 대체
// import 시: import kr.hhplus.be.server.domain.common.UserId;

import java.util.Objects;
import java.util.UUID;

/**
 * 전체 시스템에서 사용하는 통합 사용자 식별자
 * - 내부적으로 UUID를 사용하지만 String 변환을 지원
 * - 기존 코드와의 호환성 확보
 */
public final class UserId {
    private final UUID value;

    private UserId(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("사용자 ID는 null일 수 없습니다");
        }
        this.value = value;
    }

    // 팩토리 메서드들
    public static UserId of(UUID uuid) {
        return new UserId(uuid);
    }

    public static UserId ofString(String stringValue) {
        if (stringValue == null || stringValue.isBlank()) {
            throw new IllegalArgumentException("사용자 ID 문자열은 비어있을 수 없습니다");
        }
        try {
            return new UserId(UUID.fromString(stringValue));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("유효하지 않은 UUID 형식입니다: " + stringValue, e);
        }
    }

    public static UserId generate() {
        return new UserId(UUID.randomUUID());
    }

    // 접근자 메서드들
    public UUID asUUID() {
        return value;
    }

    public String asString() {
        return value.toString();
    }

    // 레거시 호환성을 위한 메서드
    public String value() {
        return value.toString();
    }

    @Override
    public boolean equals(Object obj) {
        return (this == obj) || (obj instanceof UserId other && value.equals(other.value));
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}