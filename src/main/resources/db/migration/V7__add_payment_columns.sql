-- 결제 비동기화를 위한 컬럼 추가

-- 예약 테이블에 결제 관련 컬럼 추가
ALTER TABLE reservation
    ADD COLUMN payment_requested_at DATETIME NULL COMMENT '결제 요청 시간',
    ADD COLUMN payment_fail_reason VARCHAR(500) NULL COMMENT '결제 실패 사유';

-- PAYMENT_PENDING 상태 조회를 위한 인덱스
CREATE INDEX idx_reservation_status_payment_requested
    ON reservation (status, payment_requested_at);
