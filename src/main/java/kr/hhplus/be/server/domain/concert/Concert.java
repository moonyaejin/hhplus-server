package kr.hhplus.be.server.domain.concert;


public class Concert {
    private final ConcertId id;
    private final String title;
    private final String description;

    public Concert(ConcertId id, String title, String description) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("콘서트 제목은 필수입니다");
        }
        this.id = id;
        this.title = title;
        this.description = description;
    }

    public Concert(ConcertId id, String title) {
        this(id, title, null);
    }
    public ConcertId getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
}