-- 확정된 예약만 저장 (좌석 중복 확정 방지)
CREATE TABLE IF NOT EXISTS confirmed_reservation (
                                                     id             BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                     user_id        BINARY(16) NOT NULL,
    concert_date   DATE NOT NULL,
    seat_no        INT NOT NULL,
    price          BIGINT NOT NULL,
    paid_at        DATETIME(6) NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    UNIQUE KEY uq_confirmed_unique (concert_date, seat_no)
    ) ENGINE=InnoDB;

-- 지갑
CREATE TABLE IF NOT EXISTS user_wallet (
                                           user_id     BINARY(16) PRIMARY KEY,
    balance     BIGINT NOT NULL,
    updated_at  DATETIME(6) NOT NULL
    ) ENGINE=InnoDB;

-- 지갑 거래 이력 (멱등키로 중복 방지)
CREATE TABLE IF NOT EXISTS wallet_ledger (
                                             id               BIGINT AUTO_INCREMENT PRIMARY KEY,
                                             user_id          BINARY(16) NOT NULL,
    amount           BIGINT NOT NULL,           -- 충전:+, 결제:-, 환불:+
    reason           VARCHAR(32) NOT NULL,      -- CHARGE / PAYMENT / REFUND
    idempotency_key  VARCHAR(64) DEFAULT NULL,
    created_at       DATETIME(6) NOT NULL,
    UNIQUE KEY uq_wallet_idem (user_id, idempotency_key)
    ) ENGINE=InnoDB;
