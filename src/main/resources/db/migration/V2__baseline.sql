SET search_path TO app, extensions, public;

CREATE TABLE cities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(20) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE areas (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id uuid NOT NULL REFERENCES cities(id),
    code varchar(30) NOT NULL,
    name varchar(160) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (city_id, code)
);

CREATE TABLE buildings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    area_id uuid NOT NULL REFERENCES areas(id),
    code varchar(40) NOT NULL UNIQUE,
    name varchar(180) NOT NULL,
    address_line varchar(500) NOT NULL,
    latitude numeric(9,6),
    longitude numeric(9,6),
    time_zone_id varchar(80) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_building_coordinates CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
    ),
    CONSTRAINT ck_building_details CHECK (jsonb_typeof(details) = 'object')
);
CREATE INDEX ix_building_area ON buildings(area_id, active);

CREATE TABLE properties (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    building_id uuid NOT NULL REFERENCES buildings(id),
    code varchar(50) NOT NULL UNIQUE,
    name varchar(180) NOT NULL,
    floor_label varchar(30),
    unit_number varchar(40),
    operating_model varchar(24) NOT NULL,
    property_type varchar(32) NOT NULL DEFAULT 'APARTMENT',
    area_sqm numeric(10,2),
    bedroom_count smallint NOT NULL DEFAULT 0,
    bathroom_count smallint NOT NULL DEFAULT 0,
    capacity smallint NOT NULL DEFAULT 1,
    orientation varchar(40),
    base_price_per_night numeric(19,4),
    currency char(3) NOT NULL DEFAULT 'VND',
    operational_status varchar(32) NOT NULL DEFAULT 'ONBOARDING',
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_property_operating_model CHECK (operating_model IN ('OWNED', 'LEASED_BY_VISTAY', 'MANAGEMENT')),
    CONSTRAINT ck_property_type CHECK (property_type IN ('APARTMENT', 'STUDIO', 'HOUSE', 'VILLA', 'OTHER')),
    CONSTRAINT ck_property_counts CHECK (bedroom_count >= 0 AND bathroom_count >= 0 AND capacity > 0),
    CONSTRAINT ck_property_area CHECK (area_sqm IS NULL OR area_sqm > 0),
    CONSTRAINT ck_property_base_price CHECK (base_price_per_night IS NULL OR base_price_per_night >= 0),
    CONSTRAINT ck_property_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_property_status CHECK (operational_status IN ('ONBOARDING', 'ACTIVE', 'MAINTENANCE', 'SUSPENDED', 'OFFBOARDING', 'INACTIVE')),
    CONSTRAINT ck_property_details CHECK (jsonb_typeof(details) = 'object')
);
CREATE INDEX ix_property_search ON properties(building_id, operational_status, capacity, active);
CREATE INDEX ix_property_price ON properties(base_price_per_night) WHERE active AND operational_status = 'ACTIVE';

CREATE TABLE amenities (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    amenity_scope varchar(16) NOT NULL,
    icon_media_id uuid,
    active boolean NOT NULL DEFAULT true,
    display_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_amenity_scope CHECK (amenity_scope IN ('PROPERTY', 'BUILDING', 'BOTH'))
);

CREATE TABLE property_amenities (
    property_id uuid NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    amenity_id uuid NOT NULL REFERENCES amenities(id),
    details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (property_id, amenity_id)
);

CREATE TABLE building_amenities (
    building_id uuid NOT NULL REFERENCES buildings(id) ON DELETE CASCADE,
    amenity_id uuid NOT NULL REFERENCES amenities(id),
    details jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (building_id, amenity_id)
);

-- host_id is an external reference to User Service. History is retained by
-- effective dates instead of overwriting the current owner relationship.
CREATE TABLE property_parties (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id uuid NOT NULL REFERENCES properties(id),
    host_id uuid NOT NULL,
    relationship_type varchar(32) NOT NULL,
    ownership_percentage numeric(5,2),
    valid_from date NOT NULL,
    valid_to date,
    notes varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_property_party_type CHECK (relationship_type IN ('OWNER', 'CO_OWNER', 'REPRESENTATIVE', 'BENEFICIARY')),
    CONSTRAINT ck_property_party_share CHECK (ownership_percentage IS NULL OR ownership_percentage BETWEEN 0 AND 100),
    CONSTRAINT ck_property_party_dates CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX ix_property_party_property ON property_parties(property_id, valid_from, valid_to);
CREATE INDEX ix_property_party_host ON property_parties(host_id, valid_from, valid_to);

CREATE TABLE owner_contracts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_number varchar(80) NOT NULL UNIQUE,
    start_date date NOT NULL,
    end_date date NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'DRAFT',
    document_media_id uuid,
    previous_contract_id uuid REFERENCES owner_contracts(id) ON DELETE SET NULL,
    notes varchar(2000),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_owner_contract_dates CHECK (end_date > start_date),
    CONSTRAINT ck_owner_contract_status CHECK (status IN ('DRAFT', 'ACTIVE', 'EXPIRING', 'RENEWING', 'TERMINATED', 'EXPIRED', 'CANCELLED'))
);

CREATE TABLE owner_contract_parties (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id uuid NOT NULL REFERENCES owner_contracts(id) ON DELETE CASCADE,
    host_id uuid NOT NULL,
    party_role varchar(24) NOT NULL,
    ownership_percent numeric(7,4),
    is_primary boolean NOT NULL DEFAULT false,
    signed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_contract_party_role CHECK (party_role IN ('OWNER', 'CO_OWNER', 'AUTHORIZED_REPRESENTATIVE')),
    CONSTRAINT ck_contract_party_share CHECK (ownership_percent IS NULL OR ownership_percent BETWEEN 0 AND 100),
    UNIQUE (contract_id, host_id, party_role)
);
CREATE INDEX ix_owner_contract_party_host ON owner_contract_parties(host_id, contract_id);
CREATE UNIQUE INDEX ux_owner_contract_primary_party
    ON owner_contract_parties(contract_id) WHERE is_primary;

-- Operating model and commercial terms belong to a property within a contract.
-- A contract may cover several properties and may have several host parties.
CREATE TABLE contract_property_terms (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id uuid NOT NULL REFERENCES owner_contracts(id) ON DELETE CASCADE,
    property_id uuid NOT NULL REFERENCES properties(id),
    operating_model varchar(32) NOT NULL,
    effective_from date NOT NULL,
    effective_to date NOT NULL,
    fixed_amount numeric(19,4),
    management_fee_percent numeric(7,4),
    payment_period varchar(24) NOT NULL DEFAULT 'MONTH',
    payment_cycle_count smallint NOT NULL DEFAULT 1,
    security_deposit_amount numeric(19,4) NOT NULL DEFAULT 0,
    repair_approval_threshold numeric(19,4) NOT NULL DEFAULT 0,
    currency char(3) NOT NULL DEFAULT 'VND',
    status varchar(24) NOT NULL DEFAULT 'DRAFT',
    terms jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_contract_term_model CHECK (operating_model IN ('OWNED', 'LEASED_BY_VISTAY', 'MANAGEMENT')),
    CONSTRAINT ck_contract_term_dates CHECK (effective_to > effective_from),
    CONSTRAINT ck_contract_term_amounts CHECK (
        (fixed_amount IS NULL OR fixed_amount >= 0)
        AND security_deposit_amount >= 0
        AND repair_approval_threshold >= 0
    ),
    CONSTRAINT ck_contract_term_fee CHECK (management_fee_percent IS NULL OR management_fee_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_contract_term_period CHECK (payment_period IN ('MONTH', 'QUARTER', 'YEAR', 'BOOKING') AND payment_cycle_count > 0),
    CONSTRAINT ck_contract_term_status CHECK (status IN ('DRAFT', 'ACTIVE', 'EXPIRING', 'RENEWING', 'TERMINATED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_contract_term_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_contract_term_terms CHECK (jsonb_typeof(terms) = 'object'),
    CONSTRAINT ck_contract_term_by_model CHECK (
        operating_model = 'OWNED'
        OR (operating_model = 'LEASED_BY_VISTAY' AND fixed_amount IS NOT NULL)
        OR (operating_model = 'MANAGEMENT' AND management_fee_percent IS NOT NULL)
    ),
    UNIQUE (contract_id, property_id, effective_from)
);
CREATE INDEX ix_contract_term_contract ON contract_property_terms(contract_id, status, effective_to);
CREATE INDEX ix_contract_term_property ON contract_property_terms(property_id, status, effective_to);
ALTER TABLE contract_property_terms ADD CONSTRAINT ex_active_contract_term_overlap
    EXCLUDE USING gist (
        property_id WITH =,
        daterange(effective_from, effective_to, '[)') WITH &&
    ) WHERE (status IN ('ACTIVE', 'EXPIRING', 'RENEWING'));

CREATE TABLE asset_categories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- This is the authoritative asset catalog. Condition events and repairs live
-- in Operations Service and reference this table by asset ID.
CREATE TABLE unit_assets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id uuid NOT NULL REFERENCES properties(id),
    category_id uuid REFERENCES asset_categories(id) ON DELETE SET NULL,
    asset_code varchar(60) NOT NULL,
    name varchar(180) NOT NULL,
    asset_type varchar(80),
    ownership_type varchar(24) NOT NULL,
    tracking_mode varchar(24) NOT NULL,
    area_or_room varchar(120),
    quantity numeric(12,2) NOT NULL DEFAULT 1,
    unit_label varchar(30) NOT NULL DEFAULT 'item',
    brand varchar(120),
    model varchar(120),
    serial_number varchar(180),
    acquired_at date,
    lifecycle_status varchar(24) NOT NULL DEFAULT 'ACTIVE',
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (property_id, asset_code),
    CONSTRAINT ck_unit_asset_ownership CHECK (ownership_type IN ('HOST', 'VISTAY', 'OTHER')),
    CONSTRAINT ck_unit_asset_tracking CHECK (tracking_mode IN ('INDIVIDUAL', 'QUANTITY', 'PRESENCE_ONLY')),
    CONSTRAINT ck_unit_asset_quantity CHECK (quantity >= 0),
    CONSTRAINT ck_unit_asset_lifecycle CHECK (lifecycle_status IN ('ACTIVE', 'IN_REPAIR', 'MISSING', 'DISPOSED', 'RETURNED')),
    CONSTRAINT ck_unit_asset_details CHECK (jsonb_typeof(details) = 'object')
);
CREATE INDEX ix_unit_asset_property ON unit_assets(property_id, lifecycle_status);
CREATE UNIQUE INDEX ux_unit_asset_serial ON unit_assets(serial_number) WHERE serial_number IS NOT NULL;

CREATE TABLE property_media (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id uuid NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    media_id uuid NOT NULL,
    media_role varchar(32) NOT NULL,
    room_or_area varchar(120),
    caption varchar(500),
    display_order integer NOT NULL DEFAULT 0,
    is_primary boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (property_id, media_id),
    CONSTRAINT ck_property_media_role CHECK (media_role IN ('COVER', 'GALLERY', 'ROOM', 'FLOOR_PLAN', 'DOCUMENT'))
);
CREATE UNIQUE INDEX ux_property_primary_media ON property_media(property_id) WHERE is_primary;
CREATE INDEX ix_property_media_order ON property_media(property_id, display_order);

CREATE TABLE audit_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_admin_user_id uuid,
    action varchar(100) NOT NULL,
    aggregate_type varchar(100) NOT NULL,
    aggregate_id uuid NOT NULL,
    changes jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(changes) = 'object'),
    correlation_id varchar(100),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_property_audit_aggregate ON audit_events(aggregate_type, aggregate_id, created_at DESC);

CREATE TABLE processed_events (
    event_id uuid NOT NULL,
    consumer_name varchar(120) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer_name)
);

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(100) NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version >= 0),
    event_type varchar(160) NOT NULL,
    event_version integer NOT NULL DEFAULT 1 CHECK (event_version > 0),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    available_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    publish_attempts integer NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    last_error text,
    correlation_id varchar(100),
    causation_id varchar(100)
);
CREATE INDEX ix_property_outbox_pending ON outbox_events(available_at, occurred_at) WHERE published_at IS NULL;
