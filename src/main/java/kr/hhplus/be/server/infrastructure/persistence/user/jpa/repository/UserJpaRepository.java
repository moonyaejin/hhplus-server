package kr.hhplus.be.server.infrastructure.persistence.user.jpa.repository;

import kr.hhplus.be.server.infrastructure.persistence.user.jpa.entity.UserJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {
    Optional<UserJpaEntity> findByName(String name);
}
