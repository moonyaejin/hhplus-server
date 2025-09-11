package kr.hhplus.be.server.infrastructure.persistence.user.jpa.adapter;

import kr.hhplus.be.server.domain.user.UserRepository;
import kr.hhplus.be.server.domain.user.model.User;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.infrastructure.persistence.user.jpa.entity.UserJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.user.jpa.repository.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class UserJpaAdapter implements UserRepository {

    private final UserJpaRepository repo;

    @Override
    public Optional<User> findById(UserId id) {
        return repo.findById(id.asUUID()).map(this::toDomain);
    }

    @Override
    public Optional<User> findByName(String name) {
        return repo.findByName(name).map(this::toDomain);
    }

    @Override
    public List<User> findAll() {
        return repo.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public User save(User user) {
        UserJpaEntity e = new UserJpaEntity(user.id().asUUID(), user.name());
        UserJpaEntity saved = repo.save(e);
        return toDomain(saved);
    }

    @Override
    public boolean exists(UserId id) {
        return repo.existsById(id.asUUID());
    }

    private User toDomain(UserJpaEntity e) {
        return new User(
                UserId.of(e.getId()),
                e.getName(),
                e.getCreatedAt());
    }
}