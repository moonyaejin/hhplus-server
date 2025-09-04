package kr.hhplus.be.server.domain.concert;

import java.util.Objects;

// 콘서트(공연) 도메인 모델
public final class Concert {
    private final Long id;
    private final String title;

    public Concert(Long id, String title) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title must not be blank");
        this.id = id;
        this.title = title;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Concert c)) return false;
        return Objects.equals(id, c.id) && title.equals(c.title);
    }
    @Override public int hashCode() { return Objects.hash(id, title); }
    @Override public String toString() { return "Concert{id=%s, title='%s'}".formatted(id, title); }
}
