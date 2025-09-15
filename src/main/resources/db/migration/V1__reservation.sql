-- 확정된 예약만 저장 (좌석 중복 방지)
CREATE TABLE IF NOT EXISTS confirmed_reservation (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id        BINARY(16) NOT NULL,
    concert_date   DATE NOT NULL,
    seat_no        INT NOT NULL,
    price          BIGINT NOT NULL,
    paid_at        DATETIME(6) NOT NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_confirmed_unique (concert_date, seat_no),
    KEY idx_confirmed_user (user_id)
    ) ENGINE=InnoDB;
