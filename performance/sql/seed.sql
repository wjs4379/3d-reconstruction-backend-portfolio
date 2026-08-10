USE spatial_asset_db;

INSERT INTO users (email, name, provider, provider_id, role, created_at)
SELECT 'perf-owner@example.com', 'Performance Owner', 'LOCAL', 'performance-owner', 'HOST', NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM users WHERE provider = 'LOCAL' AND provider_id = 'performance-owner'
);

SET @perf_owner_id = (
    SELECT user_id
    FROM users
    WHERE provider = 'LOCAL' AND provider_id = 'performance-owner'
    LIMIT 1
);

INSERT INTO properties (title, description, address, created_at)
SELECT '[PERF] Outbox Load Test', 'Local performance experiment fixture', 'LOCAL ONLY', NOW()
WHERE NOT EXISTS (
    SELECT 1
    FROM properties
    WHERE title = '[PERF] Outbox Load Test' AND address = 'LOCAL ONLY'
);

SET @perf_property_id = (
    SELECT property_id
    FROM properties
    WHERE title = '[PERF] Outbox Load Test' AND address = 'LOCAL ONLY'
    ORDER BY property_id
    LIMIT 1
);

INSERT INTO rooms (
    property_id,
    owner_id,
    room_name,
    price_per_night,
    max_guests,
    created_at
)
SELECT @perf_property_id, @perf_owner_id, '[PERF] Room', 10000, 2, NOW()
WHERE NOT EXISTS (
    SELECT 1
    FROM rooms
    WHERE property_id = @perf_property_id
      AND owner_id = @perf_owner_id
      AND room_name = '[PERF] Room'
);

SELECT room_id AS performance_room_id
FROM rooms
WHERE property_id = @perf_property_id
  AND owner_id = @perf_owner_id
  AND room_name = '[PERF] Room'
ORDER BY room_id
LIMIT 1;
