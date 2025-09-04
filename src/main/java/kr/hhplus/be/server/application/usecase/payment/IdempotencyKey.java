package kr.hhplus.be.server.application.usecase.payment;

import java.util.Objects;

public final class IdempotencyKey {
    private final String value;
    public IdempotencyKey(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("idempotencyKey must not be blank");
        this.value = value;
    }
    public String value() { return value; }
    @Override public boolean equals(Object o){ return (this==o) || (o instanceof IdempotencyKey k && value.equals(k.value)); }
    @Override public int hashCode(){ return Objects.hash(value); }
    @Override public String toString(){ return value; }
}
