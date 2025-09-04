package kr.hhplus.be.server.domain.user;

import kr.hhplus.be.server.domain.user.model.User;
import kr.hhplus.be.server.domain.user.model.UserId;

import java.util.List;
import java.util.Optional;

public interface UserRepository {
    Optional<User> findById(UserId id);
    Optional<User> findByName(String name);
    List<User> findAll();
    User save(User user);
    boolean exists(UserId id);
}
