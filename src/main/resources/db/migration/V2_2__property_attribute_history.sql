SET search_path TO app, extensions, public;

CREATE OR REPLACE FUNCTION protect_property_attribute_version() RETURNS trigger AS $$
BEGIN
  IF OLD.status = 'PUBLISHED' AND NEW.status = 'RETIRED' THEN
    RETURN NEW;
  END IF;
  IF OLD.status <> 'DRAFT' THEN
    RAISE EXCEPTION 'published property attribute versions are immutable';
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_property_attribute_version_immutable
BEFORE UPDATE OR DELETE ON property_attribute_definition_versions
FOR EACH ROW EXECUTE FUNCTION protect_property_attribute_version();

CREATE OR REPLACE FUNCTION prevent_property_attribute_hard_delete() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'property attribute definitions must be retired, not deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_property_attribute_no_delete
BEFORE DELETE ON property_attribute_definitions
FOR EACH ROW EXECUTE FUNCTION prevent_property_attribute_hard_delete();
