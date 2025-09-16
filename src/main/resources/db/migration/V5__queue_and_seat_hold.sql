-- 대기열 토큰 관리 테이블
CREATE TABLE IF NOT EXISTS queue_token (
                                           id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                                           token            VARCHAR(50) NOT NULL UNIQUE,
    user_id          VARCHAR(50) NOT NULL,
    status           VARCHAR(20) NOT NULL,  -- WAITING, ACTIVE, EXPIRED, USED
    waiting_position BIGINT DEFAULT NULL,
    issued_at        DATETIME(6) NOT NULL,
    activated_at     DATETIME(6) DEFAULT NULL,
    expires_at       DATETIME(6) DEFAULT NULL,
    version          BIGINT DEFAULT 0,
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    INDEX idx_token (token),
    INDEX idx_user_id (user_id),
    INDEX idx_status_position (status, waiting_position),
    INDEX idx_expires_at (expires_at)
    ) ENGINE=InnoDB;

-- 좌석 임시 점유 테이블 (Redis 대체)
CREATE TABLE IF NOT EXISTS seat_hold (
                                         id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                                         schedule_id   BIGINT NOT NULL,
                                         seat_number   INT NOT NULL,
                                         user_id       VARCHAR(50) NOT NULL,
    held_at       DATETIME(6) NOT NULL,
    expires_at    DATETIME(6) NOT NULL,
    version       BIGINT DEFAULT 0,
    created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    UNIQUE KEY uq_seat_hold (schedule_id, seat_number),
    INDEX idx_expires_at (expires_at),
    INDEX idx_user_id (user_id)
    ) ENGINE=InnoDB;

-- 예약 테이블 (임시/확정 모두 관리)
CREATE TABLE IF NOT EXISTS reservation (
                                           id                     VARCHAR(50) PRIMARY KEY,
    user_id                VARCHAR(50) NOT NULL,
    concert_schedule_id    BIGINT NOT NULL,
    seat_number            INT NOT NULL,
    price                  BIGINT NOT NULL,
    status                 VARCHAR(20) NOT NULL, -- TEMPORARY_ASSIGNED, CONFIRMED, CANCELLED, EXPIRED
    temporary_assigned_at  DATETIME(6) NOT NULL,
    confirmed_at           DATETIME(6) DEFAULT NULL,
    version                BIGINT DEFAULT 0,
    created_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    INDEX idx_user_id (user_id),
    INDEX idx_schedule_seat (concert_schedule_id, seat_number),
    INDEX idx_status (status)
    ) ENGINE=InnoDB;