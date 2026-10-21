-- Reproduce the legacy Hibernate schema for NEW databases only.
-- Existing databases must be backed up, compared with this schema, and explicitly baselined at version 1.
CREATE TABLE users (
    user_id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    provider VARCHAR(255) NOT NULL,
    provider_id VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE properties (
    property_id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    address VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (property_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rooms (
    room_id BIGINT NOT NULL AUTO_INCREMENT,
    property_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    room_name VARCHAR(255) NOT NULL,
    price_per_night INT NOT NULL,
    max_guests INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (room_id),
    CONSTRAINT fk_room_property FOREIGN KEY (property_id) REFERENCES properties (property_id),
    CONSTRAINT fk_room_owner FOREIGN KEY (owner_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE room_usages (
    usage_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    status VARCHAR(255) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NOT NULL,
    PRIMARY KEY (usage_id),
    CONSTRAINT fk_usage_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_usage_room FOREIGN KEY (room_id) REFERENCES rooms (room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE room_3d_models (
    model_id BIGINT NOT NULL AUTO_INCREMENT,
    room_id BIGINT NOT NULL,
    ai_room_id VARCHAR(255) NOT NULL,
    original_image_url TEXT NOT NULL,
    model_url TEXT NULL,
    file_size_mb DECIMAL(10,2) NULL,
    format VARCHAR(255) NULL,
    status VARCHAR(40) NOT NULL,
    room_data LONGTEXT NULL,
    uploaded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (model_id),
    CONSTRAINT uk_model_ai_room UNIQUE (ai_room_id),
    CONSTRAINT fk_model_room FOREIGN KEY (room_id) REFERENCES rooms (room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(64) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL,
    lock_token VARCHAR(64) NULL,
    locked_until DATETIME(6) NULL,
    last_error TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_outbox_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_outbox_event_aggregate_type UNIQUE (aggregate_id, event_type),
    INDEX idx_outbox_event_ready (status, next_attempt_at, id),
    INDEX idx_outbox_event_lease (status, locked_until, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
