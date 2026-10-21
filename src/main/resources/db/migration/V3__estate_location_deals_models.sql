-- Provider-verified building locations. Tokens are opaque, owner-bound and short-lived when first claimed.
-- No legacy/manual address is assigned invented coordinates or silently made public.
CREATE TABLE verified_locations (
    location_id VARCHAR(36) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    canonical_address VARCHAR(255) NOT NULL,
    road_address VARCHAR(255) NULL,
    jibun_address VARCHAR(255) NULL,
    postal_code VARCHAR(10) NULL,
    region_code VARCHAR(20) NOT NULL,
    region_name VARCHAR(100) NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (location_id),
    CONSTRAINT fk_location_owner FOREIGN KEY (owner_user_id) REFERENCES users (user_id),
    CONSTRAINT ck_location_latitude CHECK (latitude BETWEEN 32 AND 40),
    CONSTRAINT ck_location_longitude CHECK (longitude BETWEEN 123 AND 133),
    INDEX idx_location_owner_expiry (owner_user_id, expires_at),
    INDEX idx_location_coordinates (latitude, longitude)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE properties ADD COLUMN location_id VARCHAR(36) NULL,
    ADD CONSTRAINT fk_property_location FOREIGN KEY (location_id) REFERENCES verified_locations (location_id);
ALTER TABLE units ADD COLUMN location_id VARCHAR(36) NULL,
    ADD COLUMN location_visibility VARCHAR(20) NOT NULL DEFAULT 'HIDDEN',
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD CONSTRAINT fk_unit_location FOREIGN KEY (location_id) REFERENCES verified_locations (location_id),
    ADD CONSTRAINT ck_unit_location_visibility CHECK (location_visibility IN ('HIDDEN', 'BUILDING')),
    ADD CONSTRAINT ck_unit_public_coordinates CHECK (location_visibility <> 'BUILDING' OR location_id IS NOT NULL),
    ADD INDEX idx_units_location (location_id, location_visibility);

-- Conditional price fields keep unknown/inapplicable distinct from zero.
-- V2 monthly rows already satisfy these constraints and keep their identifiers/versions.
ALTER TABLE listings DROP CHECK ck_listing_deal,
    DROP CHECK ck_listing_deposit, DROP CHECK ck_listing_rent,
    MODIFY COLUMN deposit BIGINT NULL,
    MODIFY COLUMN monthly_rent BIGINT NULL,
    ADD COLUMN sale_price BIGINT NULL,
    ADD CONSTRAINT ck_listing_deal CHECK (deal_type IN ('MONTHLY_RENT', 'JEONSE', 'SALE')),
    ADD CONSTRAINT ck_listing_deposit CHECK (deposit IS NULL OR deposit BETWEEN 0 AND 1000000000000),
    ADD CONSTRAINT ck_listing_rent CHECK (monthly_rent IS NULL OR monthly_rent BETWEEN 1 AND 1000000000000),
    ADD CONSTRAINT ck_listing_sale CHECK (sale_price IS NULL OR sale_price BETWEEN 1 AND 1000000000000),
    ADD CONSTRAINT ck_listing_terms CHECK (
        (deal_type = 'MONTHLY_RENT' AND deposit IS NOT NULL AND monthly_rent IS NOT NULL AND sale_price IS NULL)
        OR (deal_type = 'JEONSE' AND deposit IS NOT NULL AND deposit > 0 AND monthly_rent IS NULL AND sale_price IS NULL)
        OR (deal_type = 'SALE' AND sale_price IS NOT NULL AND deposit IS NULL AND monthly_rent IS NULL)
    );
ALTER TABLE inquiries DROP CHECK ck_inquiry_deal,
    MODIFY COLUMN deposit_snapshot BIGINT NULL,
    MODIFY COLUMN rent_snapshot BIGINT NULL,
    ADD COLUMN sale_price_snapshot BIGINT NULL,
    ADD CONSTRAINT ck_inquiry_deal CHECK (deal_type_snapshot IN ('MONTHLY_RENT', 'JEONSE', 'SALE'));

CREATE TABLE listing_models (
    listing_id BIGINT NOT NULL,
    model_order INT NOT NULL,
    space_id BIGINT NOT NULL,
    model_asset_id BIGINT NOT NULL,
    selected_model_id BIGINT NOT NULL,
    space_name VARCHAR(255) NULL,
    PRIMARY KEY (listing_id, model_order),
    CONSTRAINT fk_listing_model_selection_listing FOREIGN KEY (listing_id) REFERENCES listings (listing_id),
    CONSTRAINT fk_listing_model_selection_space FOREIGN KEY (space_id) REFERENCES rooms (room_id),
    CONSTRAINT fk_listing_model_selection_asset FOREIGN KEY (model_asset_id) REFERENCES media_assets (asset_id),
    CONSTRAINT fk_listing_model_selection_model FOREIGN KEY (selected_model_id) REFERENCES room_3d_models (model_id),
    INDEX idx_listing_model_selection_space (listing_id, space_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Singular V2 selection remains available for old clients and becomes the first explicit space selection.
INSERT INTO listing_models(listing_id, model_order, space_id, model_asset_id, selected_model_id, space_name)
SELECT l.listing_id, 0, l.selected_space_id, l.model_asset_id, l.selected_model_id, r.room_name
FROM listings l JOIN rooms r ON r.room_id = l.selected_space_id
WHERE l.model_asset_id IS NOT NULL AND l.selected_model_id IS NOT NULL AND l.selected_space_id IS NOT NULL;

CREATE TABLE listing_favorites (
    favorite_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    listing_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (favorite_id),
    CONSTRAINT fk_favorite_user FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_favorite_listing FOREIGN KEY (listing_id) REFERENCES listings (listing_id),
    CONSTRAINT uk_favorite_user_listing UNIQUE (user_id, listing_id),
    INDEX idx_favorite_user_order (user_id, favorite_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Both the native AI analysis/reconstruction pipeline and manual GLB completion create a spatial model record.
ALTER TABLE room_3d_models MODIFY COLUMN original_image_url TEXT NULL,
    ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'LEGACY_AI',
    ADD COLUMN source_image_asset_ids LONGTEXT NULL,
    ADD CONSTRAINT ck_model_source_type CHECK (source_type IN ('LEGACY_AI', 'AI_PIPELINE', 'MANUAL_UPLOAD'));
ALTER TABLE upload_sessions ADD COLUMN space_id BIGINT NULL,
    ADD COLUMN model_id BIGINT NULL,
    ADD COLUMN source_image_asset_ids LONGTEXT NULL,
    ADD CONSTRAINT fk_upload_space FOREIGN KEY (space_id) REFERENCES rooms (room_id),
    ADD CONSTRAINT fk_upload_model FOREIGN KEY (model_id) REFERENCES room_3d_models (model_id);
