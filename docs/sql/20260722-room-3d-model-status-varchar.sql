-- Historical pre-Flyway migration. Do not run on a fresh/current schema.
-- Current schemas use src/main/resources/db/migration via Flyway.
-- 기존 MySQL 스키마가 Hibernate native ENUM으로 생성된 경우 한 번 실행합니다.
-- VARCHAR로 바꿔 두면 애플리케이션 상태가 추가되어도 DB ENUM 목록과 충돌하지 않습니다.
ALTER TABLE room_3d_models
    MODIFY COLUMN status VARCHAR(40) NOT NULL;
