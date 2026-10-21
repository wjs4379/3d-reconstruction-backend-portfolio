-- Existing details remain unknown, never invented false/zero values.
ALTER TABLE units
    ADD COLUMN building_type VARCHAR(20) NULL,
    ADD COLUMN total_floors INT NULL,
    ADD COLUMN room_count INT NULL,
    ADD COLUMN bathroom_count INT NULL,
    ADD COLUMN facing_direction VARCHAR(20) NULL,
    ADD COLUMN has_elevator BOOLEAN NULL,
    ADD COLUMN has_parking BOOLEAN NULL,
    ADD COLUMN pets_allowed BOOLEAN NULL,
    ADD COLUMN furnished BOOLEAN NULL,
    ADD CONSTRAINT ck_unit_building_type CHECK (building_type IS NULL OR building_type IN ('APARTMENT','OFFICETEL','VILLA','HOUSE','COMMERCIAL','OTHER')),
    ADD CONSTRAINT ck_unit_total_floors CHECK (total_floors IS NULL OR total_floors BETWEEN 1 AND 200),
    ADD CONSTRAINT ck_unit_room_count CHECK (room_count IS NULL OR room_count BETWEEN 0 AND 99),
    ADD CONSTRAINT ck_unit_bathroom_count CHECK (bathroom_count IS NULL OR bathroom_count BETWEEN 0 AND 99),
    ADD CONSTRAINT ck_unit_floor_consistency CHECK (floor IS NULL OR total_floors IS NULL OR floor <= total_floors),
    ADD CONSTRAINT ck_unit_direction CHECK (facing_direction IS NULL OR facing_direction IN ('EAST','WEST','SOUTH','NORTH','NORTHEAST','NORTHWEST','SOUTHEAST','SOUTHWEST')),
    ADD CONSTRAINT ck_unit_elevator CHECK (has_elevator IS NULL OR has_elevator IN (0,1)),
    ADD CONSTRAINT ck_unit_parking CHECK (has_parking IS NULL OR has_parking IN (0,1)),
    ADD CONSTRAINT ck_unit_pets CHECK (pets_allowed IS NULL OR pets_allowed IN (0,1)),
    ADD CONSTRAINT ck_unit_furnished CHECK (furnished IS NULL OR furnished IN (0,1));

ALTER TABLE listings DROP CHECK ck_listing_status,
    ADD COLUMN completed_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_listing_status CHECK (status IN ('DRAFT','PUBLISHED','HIDDEN','COMPLETED')),
    ADD CONSTRAINT ck_listing_completion CHECK ((status='COMPLETED' AND completed_at IS NOT NULL) OR (status<>'COMPLETED' AND completed_at IS NULL));
-- Details remain nullable; selective filters and price ordering should be measured before adding speculative indexes.
