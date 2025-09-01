package kr.hhplus.be.server.reservation.port.out;

public interface QueuePort {
    boolean isActive(String token);
    String userIdOf(String token);
    void expire(String token);
}

