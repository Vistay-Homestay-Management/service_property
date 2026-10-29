SET search_path TO app, extensions, public;

CREATE TABLE operating_branches (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(40) NOT NULL UNIQUE,
    name varchar(180) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE property_branch_memberships (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    property_id uuid NOT NULL REFERENCES properties(id) ON DELETE RESTRICT,
    branch_id uuid NOT NULL REFERENCES operating_branches(id) ON DELETE RESTRICT,
    membership_version bigint NOT NULL CHECK (membership_version >= 1),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    assigned_by_admin_user_id uuid,
    reason varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_property_branch_membership_dates
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_property_branch_membership_reason
        CHECK (length(trim(reason)) >= 8),
    UNIQUE (property_id, membership_version)
);

ALTER TABLE property_branch_memberships ADD CONSTRAINT ex_property_branch_membership_overlap
    EXCLUDE USING gist (
        property_id WITH =,
        tstzrange(effective_from, effective_to, '[)') WITH &&
    );

CREATE UNIQUE INDEX ux_property_branch_membership_current
    ON property_branch_memberships(property_id)
    WHERE effective_to IS NULL;
CREATE INDEX ix_property_branch_membership_branch
    ON property_branch_memberships(branch_id, effective_from, effective_to);
