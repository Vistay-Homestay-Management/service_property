SET search_path TO app, extensions, public;

-- floor_label already exists on the legacy properties table and remains the
-- compatibility source for unit floor data. Access passwords are never stored
-- as plaintext; this field points to the approved secret-manager entry.
ALTER TABLE properties
    ADD COLUMN access_password_secret_ref varchar(180);

ALTER TABLE properties
    ADD CONSTRAINT ck_property_access_password_ref
    CHECK (access_password_secret_ref IS NULL OR btrim(access_password_secret_ref) <> '');

COMMENT ON COLUMN properties.access_password_secret_ref IS
    'Opaque secret-manager reference for unit access password; never store the raw password here.';

CREATE INDEX ix_property_floor ON properties(building_id, floor_label, active);
