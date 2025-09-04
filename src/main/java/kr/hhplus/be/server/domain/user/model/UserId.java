package kr.hhplus.be.server.domain.user.model;

import java.util.Objects;
import java.util.UUID;

public final class UserId {
    private final UUID value;

    public UserId(UUID value) {
        if (value == null) throw new IllegalArgumentException("userId cannot be null");
        this.value = value;
    }
    public static UserId ofString(String s) { return new UserId(UUID.fromString(s)); }
    public static UserId newId() { return new UserId(UUID.randomUUID()); }

    public UUID value() { return value; }
    public String asString() { return value.toString(); }

    @Override public boolean equals(Object o){ return (this==o) || (o instanceof UserId u && value.equals(u.value)); }
    @Override public int hashCode(){ return Objects.hash(value); }
    @Override public String toString(){ return value.toString(); }
}
