-- user_wallet 테이블에 version 컬럼 추가
ALTER TABLE user_wallet
    ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;

-- 필요한 경우 다른 테이블에도 version 추가
ALTER TABLE wallet_ledger
    ADD COLUMN version BIGINT DEFAULT 0;