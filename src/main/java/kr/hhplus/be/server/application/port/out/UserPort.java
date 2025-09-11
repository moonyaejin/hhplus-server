package kr.hhplus.be.server.application.port.out;

import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.user.model.User;

import java.util.List;
import java.util.Optional;

public interface UserPort {
    Optional<User> findById(UserId id);
    Optional<User> findByName(String name);
    List<User> findAll();
    User save(User user);
    boolean exists(UserId id);
}
