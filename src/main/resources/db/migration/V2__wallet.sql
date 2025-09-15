-- 지갑
CREATE TABLE IF NOT EXISTS user_wallet (
    user_id     BINARY(16) PRIMARY KEY,
    balance     BIGINT NOT NULL,
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
    ) ENGINE=InnoDB;

-- 지갑 거래 이력 (멱등키로 중복 방지)
CREATE TABLE IF NOT EXISTS wallet_ledger (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BINARY(16) NOT NULL,
    amount           BIGINT NOT NULL,           -- 충전:+, 결제:-, 환불:+
    reason           VARCHAR(32) NOT NULL,      -- CHARGE / PAYMENT / PAYMENT_REFUND 등 enum성 텍스트
    idempotency_key  VARCHAR(64) DEFAULT NULL,
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_wallet_idem (user_id, idempotency_key),
    KEY idx_wallet_user (user_id)
    -- FK 옵션
    -- , CONSTRAINT fk_wallet_ledger_user
    --   FOREIGN KEY (user_id) REFERENCES user_wallet(user_id)
    ) ENGINE=InnoDB;
