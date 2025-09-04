CREATE TABLE IF NOT EXISTS concert (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(100) NOT NULL,
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
    ) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS concert_schedule (
                                                id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                                                concert_id    BIGINT NOT NULL,
                                                concert_date  DATE NOT NULL,
                                                seat_count    INT  NOT NULL,
                                                created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_concert_date (concert_id, concert_date),
    CONSTRAINT fk_schedule_concert FOREIGN KEY (concert_id) REFERENCES concert(id)
    ) ENGINE=InnoDB;
