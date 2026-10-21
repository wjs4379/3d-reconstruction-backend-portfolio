-- Dimensions come only from the verified immutable bytes, never client input.
-- Preserve old assets without inventing metadata or touching their file/model links.
ALTER TABLE media_assets
    ADD COLUMN pixel_width INT NULL,
    ADD COLUMN pixel_height INT NULL,
    ADD CONSTRAINT ck_media_image_dimensions CHECK (
        (pixel_width IS NULL AND pixel_height IS NULL) OR
        (kind = 'IMAGE' AND status = 'VERIFIED' AND
         pixel_width IS NOT NULL AND pixel_height IS NOT NULL AND
         pixel_width BETWEEN 1 AND 8192 AND pixel_height BETWEEN 1 AND 8192 AND
         CAST(pixel_width AS SIGNED) * CAST(pixel_height AS SIGNED) <= 12000000)
    );
