-- Reject duplicate social identities before changing other legacy tables.
-- Never delete duplicate users automatically; resolve them with ownership/data review before retrying.
ALTER TABLE users ADD CONSTRAINT uk_user_social_identity UNIQUE (provider, provider_id);

-- Unknown legacy ownership stays NULL until an operator reviews it.
ALTER TABLE properties ADD COLUMN creator_user_id BIGINT NULL,
    ADD CONSTRAINT fk_property_creator FOREIGN KEY (creator_user_id) REFERENCES users (user_id);

CREATE TABLE units (
    unit_id BIGINT NOT NULL AUTO_INCREMENT,
    property_id BIGINT NOT NULL,
    creator_user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    region_code VARCHAR(20) NOT NULL,
    public_address VARCHAR(255) NULL,
    floor INT NULL,
    area_sqm DECIMAL(10,2) NULL,
    available_from DATE NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (unit_id),
    CONSTRAINT fk_unit_property FOREIGN KEY (property_id) REFERENCES properties (property_id),
    CONSTRAINT fk_unit_creator FOREIGN KEY (creator_user_id) REFERENCES users (user_id),
    CONSTRAINT ck_unit_area CHECK (area_sqm IS NULL OR area_sqm > 0),
    INDEX idx_units_creator (creator_user_id),
    INDEX idx_units_region (region_code, unit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Preserve legacy prices, spaces, model IDs, AI IDs and usages; do not guess which Rooms form a Unit.
ALTER TABLE rooms MODIFY COLUMN price_per_night INT NULL,
    MODIFY COLUMN max_guests INT NULL,
    ADD COLUMN unit_id BIGINT NULL,
    ADD CONSTRAINT fk_room_unit FOREIGN KEY (unit_id) REFERENCES units (unit_id);

CREATE TABLE media_assets (
    asset_id BIGINT NOT NULL AUTO_INCREMENT,
    unit_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    object_version VARCHAR(255) NULL,
    size_bytes BIGINT NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    checksum VARCHAR(64) NULL,
    source_model_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (asset_id),
    CONSTRAINT fk_asset_unit FOREIGN KEY (unit_id) REFERENCES units (unit_id),
    CONSTRAINT fk_asset_owner FOREIGN KEY (owner_user_id) REFERENCES users (user_id),
    CONSTRAINT fk_asset_source_model FOREIGN KEY (source_model_id) REFERENCES room_3d_models (model_id),
    CONSTRAINT ck_asset_kind CHECK (kind IN ('IMAGE', 'MODEL')),
    CONSTRAINT ck_asset_status CHECK (status IN ('PENDING', 'VERIFIED')),
    CONSTRAINT ck_asset_size CHECK (size_bytes > 0),
    INDEX idx_assets_unit (unit_id, asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE upload_sessions (
    id VARCHAR(36) NOT NULL,
    unit_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    staging_key VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    asset_id BIGINT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_upload_unit FOREIGN KEY (unit_id) REFERENCES units (unit_id),
    CONSTRAINT fk_upload_owner FOREIGN KEY (owner_user_id) REFERENCES users (user_id),
    CONSTRAINT fk_upload_asset FOREIGN KEY (asset_id) REFERENCES media_assets (asset_id),
    CONSTRAINT ck_upload_kind CHECK (kind IN ('IMAGE', 'MODEL')),
    CONSTRAINT ck_upload_size CHECK (size_bytes > 0),
    INDEX idx_upload_expiration (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE listings (
    listing_id BIGINT NOT NULL AUTO_INCREMENT,
    unit_id BIGINT NOT NULL,
    deal_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NULL,
    deposit BIGINT NOT NULL,
    monthly_rent BIGINT NOT NULL,
    management_fee BIGINT NULL,
    management_fee_includes VARCHAR(500) NULL,
    representative_asset_id BIGINT NULL,
    model_asset_id BIGINT NULL,
    selected_model_id BIGINT NULL,
    selected_space_id BIGINT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (listing_id),
    CONSTRAINT uk_listing_unit_deal UNIQUE (unit_id, deal_type),
    CONSTRAINT fk_listing_unit FOREIGN KEY (unit_id) REFERENCES units (unit_id),
    CONSTRAINT fk_listing_representative FOREIGN KEY (representative_asset_id) REFERENCES media_assets (asset_id),
    CONSTRAINT fk_listing_model_asset FOREIGN KEY (model_asset_id) REFERENCES media_assets (asset_id),
    CONSTRAINT fk_listing_model FOREIGN KEY (selected_model_id) REFERENCES room_3d_models (model_id),
    CONSTRAINT fk_listing_space FOREIGN KEY (selected_space_id) REFERENCES rooms (room_id),
    CONSTRAINT ck_listing_deal CHECK (deal_type = 'MONTHLY_RENT'),
    CONSTRAINT ck_listing_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'HIDDEN')),
    CONSTRAINT ck_listing_deposit CHECK (deposit BETWEEN 0 AND 1000000000000),
    CONSTRAINT ck_listing_rent CHECK (monthly_rent BETWEEN 1 AND 1000000000000),
    CONSTRAINT ck_listing_fee CHECK (management_fee IS NULL OR management_fee BETWEEN 0 AND 1000000000),
    CONSTRAINT ck_listing_public_image CHECK (status <> 'PUBLISHED' OR (representative_asset_id IS NOT NULL AND published_at IS NOT NULL)),
    CONSTRAINT ck_listing_model_refs CHECK ((model_asset_id IS NULL AND selected_model_id IS NULL AND selected_space_id IS NULL)
        OR (model_asset_id IS NOT NULL AND selected_model_id IS NOT NULL AND selected_space_id IS NOT NULL)),
    INDEX idx_listing_public_order (status, published_at, listing_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE listing_photos (
    listing_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    photo_order INT NOT NULL,
    PRIMARY KEY (listing_id, photo_order),
    CONSTRAINT fk_photo_listing FOREIGN KEY (listing_id) REFERENCES listings (listing_id),
    CONSTRAINT fk_photo_asset FOREIGN KEY (asset_id) REFERENCES media_assets (asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inquiries (
    inquiry_id BIGINT NOT NULL AUTO_INCREMENT,
    listing_id BIGINT NOT NULL,
    unit_id BIGINT NOT NULL,
    author_user_id BIGINT NOT NULL,
    listing_version BIGINT NOT NULL,
    title_snapshot VARCHAR(200) NOT NULL,
    deal_type_snapshot VARCHAR(20) NOT NULL,
    deposit_snapshot BIGINT NOT NULL,
    rent_snapshot BIGINT NOT NULL,
    management_fee_snapshot BIGINT NULL,
    management_fee_includes_snapshot VARCHAR(500) NULL,
    message VARCHAR(2000) NOT NULL,
    reply VARCHAR(2000) NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    responded_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    PRIMARY KEY (inquiry_id),
    CONSTRAINT fk_inquiry_listing FOREIGN KEY (listing_id) REFERENCES listings (listing_id),
    CONSTRAINT fk_inquiry_unit FOREIGN KEY (unit_id) REFERENCES units (unit_id),
    CONSTRAINT fk_inquiry_author FOREIGN KEY (author_user_id) REFERENCES users (user_id),
    CONSTRAINT ck_inquiry_status CHECK (status IN ('OPEN', 'RESPONDED', 'CLOSED')),
    CONSTRAINT ck_inquiry_deal CHECK (deal_type_snapshot = 'MONTHLY_RENT'),
    INDEX idx_inquiry_author (author_user_id, inquiry_id),
    INDEX idx_inquiry_unit (unit_id, inquiry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
