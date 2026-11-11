SET search_path TO app, extensions, public;

-- Floors are first-class building children. A property belongs to exactly one
-- floor, so building ownership is derived through building_floors rather than
-- duplicated on the property row.
CREATE TABLE building_floors (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    building_id uuid NOT NULL REFERENCES buildings(id) ON DELETE RESTRICT,
    code varchar(40) NOT NULL,
    name varchar(80) NOT NULL,
    floor_number integer,
    sort_order integer NOT NULL DEFAULT 0,
    floor_type varchar(24) NOT NULL DEFAULT 'RESIDENTIAL',
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_building_floor_code CHECK (btrim(code) <> ''),
    CONSTRAINT ck_building_floor_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_building_floor_type CHECK (floor_type IN (
        'RESIDENTIAL', 'GROUND', 'BASEMENT', 'AMENITY', 'TECHNICAL', 'OTHER'
    )),
    CONSTRAINT ck_building_floor_details CHECK (jsonb_typeof(details) = 'object'),
    UNIQUE (building_id, code),
    UNIQUE (building_id, name)
);
CREATE INDEX ix_building_floor_order
    ON building_floors(building_id, active, sort_order, floor_number);

-- Preserve every legacy floor label. Empty labels become an explicit holding
-- floor so the new required relationship never loses an existing property.
WITH legacy_floors AS (
    SELECT DISTINCT
        building_id,
        COALESCE(NULLIF(btrim(floor_label), ''), 'Chưa phân tầng') AS floor_name
    FROM properties
), numbered AS (
    SELECT
        building_id,
        floor_name,
        row_number() OVER (PARTITION BY building_id ORDER BY floor_name) AS floor_order
    FROM legacy_floors
)
INSERT INTO building_floors (building_id, code, name, floor_number, sort_order, floor_type)
SELECT
    building_id,
    'LEGACY-' || lpad(floor_order::text, 3, '0'),
    floor_name,
    CASE
        WHEN floor_name ~ '-?[0-9]+' THEN substring(floor_name from '-?[0-9]+')::integer
        ELSE NULL
    END,
    floor_order::integer,
    CASE WHEN floor_name = 'Chưa phân tầng' THEN 'OTHER' ELSE 'RESIDENTIAL' END
FROM numbered;

ALTER TABLE properties ADD COLUMN floor_id uuid;

UPDATE properties AS property
SET floor_id = floor.id
FROM building_floors AS floor
WHERE floor.building_id = property.building_id
  AND floor.name = COALESCE(NULLIF(btrim(property.floor_label), ''), 'Chưa phân tầng');

ALTER TABLE properties
    ALTER COLUMN floor_id SET NOT NULL,
    ADD CONSTRAINT fk_property_floor
        FOREIGN KEY (floor_id) REFERENCES building_floors(id) ON DELETE RESTRICT;

DROP INDEX IF EXISTS ix_property_floor;
DROP INDEX IF EXISTS ix_property_search;
ALTER TABLE properties DROP CONSTRAINT IF EXISTS ck_property_type;
ALTER TABLE properties DROP CONSTRAINT IF EXISTS properties_building_id_fkey;
ALTER TABLE properties
    DROP COLUMN floor_label,
    DROP COLUMN property_type,
    DROP COLUMN building_id;

CREATE INDEX ix_property_search
    ON properties(floor_id, operational_status, capacity, active);

-- Property type is no longer a fixed domain concept. Any future classification
-- is an admin-defined attribute, not a column or an applicability discriminator.
UPDATE property_attribute_definition_versions
SET applicability = applicability - 'propertyTypes'
WHERE applicability ? 'propertyTypes';

COMMENT ON TABLE building_floors IS
    'Authoritative floor catalog scoped to a building; properties reference a floor directly.';
COMMENT ON COLUMN properties.floor_id IS
    'Required floor relationship. The parent building is derived from building_floors.';
