package kr.hhplus.be.server.infrastructure.redis.lock;

/**
 * 분산락 획득 실패 시 발생하는 예외
 */
public class LockAcquisitionException extends RuntimeException {

    public LockAcquisitionException(String message) {
        super(message);
    }

    public LockAcquisitionException(String message, Throwable cause) {
        super(message, cause);
    }

    public static LockAcquisitionException of(String lockKey, int maxRetries) {
        return new LockAcquisitionException(
                String.format("락 획득 실패: key = %s, 최대 재시도 횟수 %d 초과", lockKey, maxRetries)
        );
    }
}
