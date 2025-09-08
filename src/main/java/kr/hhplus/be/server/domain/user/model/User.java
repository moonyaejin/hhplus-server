package kr.hhplus.be.server.domain.user.model;

import java.time.Instant;
import java.util.Objects;

public class User {
    private final UserId id;
    private String name;
    private final Instant createdAt;

    public User(UserId id, String name, Instant createdAt) {
        if (id == null) throw new IllegalArgumentException("id cannot be null");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name cannot be null or blank");
        this.id = id;
        this.name = name;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public UserId id() { return id; }
    public String name() { return name; }
    public Instant createdAt() { return createdAt; }

    public void rename(String newName) {
        if (newName == null || newName.isBlank()) throw new IllegalArgumentException("newName cannot be null or blank");
        this.name = newName;
    }
}
