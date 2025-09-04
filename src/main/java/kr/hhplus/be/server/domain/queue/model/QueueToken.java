package kr.hhplus.be.server.domain.queue.model;

import java.util.Objects;

public final class QueueToken {
    private final String value;

    public QueueToken(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("token must not be blank");
        this.value = value;
    }

    public String value() { return value; }

    @Override public String toString() { return value; }
    @Override public boolean equals(Object o) {
        return (this == o) || (o instanceof QueueToken t && value.equals(t.value));
    }
    @Override public int hashCode() { return Objects.hash(value); }
}
