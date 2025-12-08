-- ===========================================
-- 콘서트 예약 서비스 부하 테스트용 시드 데이터
-- 고정 UUID 패턴 사용
-- ===========================================

-- 기존 테스트 데이터 삭제
DELETE FROM user_wallet WHERE user_id IN (SELECT id FROM user_account WHERE name LIKE 'test-user-%');
DELETE FROM user_account WHERE name LIKE 'test-user-%';

-- 1. 콘서트 데이터
INSERT INTO concert (title, created_at, updated_at) VALUES
                                                        ('아이유 콘서트 2025', NOW(), NOW()),
                                                        ('BTS 월드 투어', NOW(), NOW()),
                                                        ('블랙핑크 인 유어 에어리어', NOW(), NOW())
    ON DUPLICATE KEY UPDATE title = VALUES(title);

-- 2. 콘서트 스케줄 (각 콘서트당 3일, 좌석 50개)
INSERT INTO concert_schedule (concert_id, concert_date, seat_count, created_at, updated_at) VALUES
                                                                                                (1, DATE_ADD(CURDATE(), INTERVAL 7 DAY), 50, NOW(), NOW()),
                                                                                                (1, DATE_ADD(CURDATE(), INTERVAL 8 DAY), 50, NOW(), NOW()),
                                                                                                (1, DATE_ADD(CURDATE(), INTERVAL 9 DAY), 50, NOW(), NOW()),
                                                                                                (2, DATE_ADD(CURDATE(), INTERVAL 14 DAY), 50, NOW(), NOW()),
                                                                                                (2, DATE_ADD(CURDATE(), INTERVAL 15 DAY), 50, NOW(), NOW()),
                                                                                                (2, DATE_ADD(CURDATE(), INTERVAL 16 DAY), 50, NOW(), NOW()),
                                                                                                (3, DATE_ADD(CURDATE(), INTERVAL 21 DAY), 50, NOW(), NOW()),
                                                                                                (3, DATE_ADD(CURDATE(), INTERVAL 22 DAY), 50, NOW(), NOW()),
                                                                                                (3, DATE_ADD(CURDATE(), INTERVAL 23 DAY), 50, NOW(), NOW())
    ON DUPLICATE KEY UPDATE seat_count = VALUES(seat_count);

-- 3. 테스트 유저 생성 (500명) - 고정 UUID 패턴
-- UUID 형식: 00000000-0000-0000-0000-000000000XXX
DROP PROCEDURE IF EXISTS create_test_users;

DELIMITER //
CREATE PROCEDURE create_test_users()
BEGIN
    DECLARE i INT DEFAULT 1;
    DECLARE user_uuid_str VARCHAR(36);
    DECLARE user_uuid BINARY(16);
    DECLARE user_name VARCHAR(50);

    WHILE i <= 500 DO
        SET user_name = CONCAT('test-user-', LPAD(i, 4, '0'));
        -- 고정 UUID 패턴: 00000000-0000-0000-0000-000000000001 ~ 500
        SET user_uuid_str = CONCAT('00000000-0000-0000-0000-', LPAD(i, 12, '0'));
        SET user_uuid = UUID_TO_BIN(user_uuid_str);

        -- 유저 생성
        INSERT IGNORE INTO user_account (id, name, created_at, updated_at)
        VALUES (user_uuid, user_name, NOW(), NOW());

        -- 지갑 생성 (초기 잔액 100만원)
        INSERT IGNORE INTO user_wallet (user_id, balance, version, updated_at)
        VALUES (user_uuid, 1000000, 0, NOW());

        SET i = i + 1;
END WHILE;
END //
DELIMITER ;

CALL create_test_users();
DROP PROCEDURE IF EXISTS create_test_users;

-- 4. 결과 확인
SELECT '=== 시드 데이터 생성 완료 ===' AS status;
SELECT COUNT(*) AS user_count FROM user_account;
SELECT COUNT(*) AS wallet_count FROM user_wallet;
SELECT name, BIN_TO_UUID(id) AS uuid FROM user_account LIMIT 5;