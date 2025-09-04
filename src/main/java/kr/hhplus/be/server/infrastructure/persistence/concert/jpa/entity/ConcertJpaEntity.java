package kr.hhplus.be.server.infrastructure.persistence.concert.jpa.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "concert")
public class ConcertJpaEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected ConcertJpaEntity() {}
    public ConcertJpaEntity(String title) { this.title = title; }

    public Long getId() { return id; }
    public String getTitle() { return title; }

    public void setTitle(String title) { this.title = title; } // 관리용
}
