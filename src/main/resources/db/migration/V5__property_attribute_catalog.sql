-- Forward migration after the existing V1-V4 property schema history.
SET search_path TO app, extensions, public;

CREATE TABLE property_attribute_definitions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(80) NOT NULL UNIQUE,
    active boolean NOT NULL DEFAULT true,
    row_version bigint NOT NULL DEFAULT 1 CHECK (row_version >= 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_property_attribute_code CHECK (code ~ '^[a-z][a-z0-9-]{1,79}$')
);

CREATE TABLE property_attribute_definition_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    definition_id uuid NOT NULL REFERENCES property_attribute_definitions(id) ON DELETE RESTRICT,
    version integer NOT NULL CHECK (version > 0),
    status varchar(24) NOT NULL DEFAULT 'DRAFT',
    label varchar(160) NOT NULL,
    description varchar(500),
    group_code varchar(64) NOT NULL,
    data_type varchar(24) NOT NULL,
    validation jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(validation) = 'object'),
    applicability jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(applicability) = 'object'),
    visibility varchar(16) NOT NULL DEFAULT 'ADMIN',
    required_on_publish boolean NOT NULL DEFAULT false,
    filterable boolean NOT NULL DEFAULT false,
    display_order integer NOT NULL DEFAULT 0,
    unit_label varchar(32),
    fingerprint char(64) NOT NULL,
    created_by_account_id uuid NOT NULL,
    approved_by_account_id uuid,
    reason varchar(500) NOT NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_property_attribute_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED', 'REJECTED')),
    CONSTRAINT ck_property_attribute_type CHECK (data_type IN (
      'TEXT', 'LONG_TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'SINGLE_SELECT', 'MULTI_SELECT'
    )),
    CONSTRAINT ck_property_attribute_visibility CHECK (visibility IN ('PUBLIC', 'HOST', 'ADMIN')),
    CONSTRAINT ck_property_attribute_checker CHECK (
      approved_by_account_id IS NULL OR approved_by_account_id <> created_by_account_id
    ),
    UNIQUE (definition_id, version),
    UNIQUE (fingerprint)
);
CREATE INDEX ix_property_attribute_catalog
  ON property_attribute_definition_versions(status, group_code, display_order);

CREATE UNIQUE INDEX ux_property_attribute_published
  ON property_attribute_definition_versions(definition_id) WHERE status = 'PUBLISHED';

CREATE TABLE property_attribute_options (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    definition_version_id uuid NOT NULL REFERENCES property_attribute_definition_versions(id) ON DELETE CASCADE,
    code varchar(64) NOT NULL,
    label varchar(120) NOT NULL,
    display_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_property_attribute_option_code CHECK (code ~ '^[a-z][a-z0-9-]{1,63}$'),
    UNIQUE (definition_version_id, code)
);

CREATE TABLE property_attribute_values (
    property_id uuid NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    definition_id uuid NOT NULL REFERENCES property_attribute_definitions(id) ON DELETE RESTRICT,
    definition_version_id uuid NOT NULL REFERENCES property_attribute_definition_versions(id) ON DELETE RESTRICT,
    value jsonb NOT NULL,
    value_version bigint NOT NULL DEFAULT 1 CHECK (value_version >= 1),
    updated_by_account_id uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (property_id, definition_id)
);

CREATE TABLE property_attribute_search_values (
    property_id uuid NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    definition_id uuid NOT NULL REFERENCES property_attribute_definitions(id) ON DELETE CASCADE,
    ordinal integer NOT NULL DEFAULT 0,
    text_value varchar(1000),
    numeric_value numeric(30,8),
    boolean_value boolean,
    date_value date,
    option_code varchar(64),
    PRIMARY KEY (property_id, definition_id, ordinal),
    CONSTRAINT ck_property_attribute_search_one_value CHECK (
      num_nonnulls(text_value, numeric_value, boolean_value, date_value, option_code) = 1
    )
);
CREATE INDEX ix_property_attribute_search_text
  ON property_attribute_search_values(definition_id, text_value) WHERE text_value IS NOT NULL;
CREATE INDEX ix_property_attribute_search_number
  ON property_attribute_search_values(definition_id, numeric_value) WHERE numeric_value IS NOT NULL;
CREATE INDEX ix_property_attribute_search_boolean
  ON property_attribute_search_values(definition_id, boolean_value) WHERE boolean_value IS NOT NULL;
CREATE INDEX ix_property_attribute_search_date
  ON property_attribute_search_values(definition_id, date_value) WHERE date_value IS NOT NULL;
CREATE INDEX ix_property_attribute_search_option
  ON property_attribute_search_values(definition_id, option_code) WHERE option_code IS NOT NULL;

CREATE TABLE property_attribute_idempotency (
    actor_account_id uuid NOT NULL,
    idempotency_key varchar(160) NOT NULL,
    request_hash char(64) NOT NULL,
    resource_id uuid NOT NULL,
    response jsonb NOT NULL CHECK (jsonb_typeof(response) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (actor_account_id, idempotency_key)
);
