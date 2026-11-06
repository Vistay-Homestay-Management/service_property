package com.vistay.property.application;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PropertyAttributeService {
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9-]{1,79}$");
    private static final Set<String> TYPES = Set.of("TEXT", "LONG_TEXT", "INTEGER", "DECIMAL",
        "BOOLEAN", "DATE", "SINGLE_SELECT", "MULTI_SELECT");
    private static final Set<String> VISIBILITIES = Set.of("PUBLIC", "HOST", "ADMIN");
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public PropertyAttributeService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<AttributeDefinition> catalog(boolean includeDrafts) {
        return jdbc.sql("""
            SELECT d.id, d.code, d.active, d.row_version,
                   v.id AS version_id, v.version, v.status, v.label, v.description,
                   v.group_code, v.data_type, v.validation::text AS validation_json,
                   v.applicability::text AS applicability_json, v.visibility,
                   v.required_on_publish, v.filterable, v.display_order, v.unit_label,
                   v.created_by_account_id, v.approved_by_account_id, v.reason
            FROM property_attribute_definitions d
            JOIN LATERAL (
              SELECT candidate.* FROM property_attribute_definition_versions candidate
              WHERE candidate.definition_id = d.id
                AND (:includeDrafts OR candidate.status = 'PUBLISHED')
              ORDER BY CASE candidate.status WHEN 'PUBLISHED' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
                       candidate.version DESC LIMIT 1
            ) v ON true
            WHERE d.active OR :includeDrafts
            ORDER BY v.group_code, v.display_order, d.code
            """).param("includeDrafts", includeDrafts).query(AttributeDefinition.class).list();
    }

    public List<PropertyAttributeValue> values(UUID propertyId, Set<String> scopes, UUID actorId) {
        assertPropertyAccess(propertyId, scopes, actorId);
        return jdbc.sql("""
            SELECT d.code, v.label, v.data_type, v.visibility, value.value::text AS value_json,
                   value.value_version, value.updated_at
            FROM property_attribute_values value
            JOIN property_attribute_definitions d ON d.id = value.definition_id
            JOIN property_attribute_definition_versions v ON v.id = value.definition_version_id
            WHERE value.property_id = :propertyId AND d.active AND v.status = 'PUBLISHED'
            ORDER BY v.group_code, v.display_order, d.code
            """).param("propertyId", propertyId).query(PropertyAttributeValue.class).list();
    }

    @Transactional
    public AttributeDefinition createDraft(UUID actorId, AttributeDraftInput input) {
        validateDraft(input);
        var requestHash = hash(json(input));
        var replay = idempotent(actorId, input.idempotencyKey(), requestHash);
        if (replay != null) return find(replay, true);

        UUID definitionId = jdbc.sql("""
            INSERT INTO property_attribute_definitions(code) VALUES (:code)
            ON CONFLICT (code) DO UPDATE SET row_version = property_attribute_definitions.row_version + 1,
                                             updated_at = now()
            RETURNING id
            """).param("code", normalizeCode(input.code())).query(UUID.class).single();
        var nextVersion = jdbc.sql("""
            SELECT COALESCE(MAX(version), 0) + 1
            FROM property_attribute_definition_versions WHERE definition_id = :id
            """).param("id", definitionId).query(Integer.class).single();
        UUID versionId = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO property_attribute_definition_versions(
              id, definition_id, version, status, label, description, group_code, data_type,
              validation, applicability, visibility, required_on_publish, filterable,
              display_order, unit_label, fingerprint, created_by_account_id, reason
            ) VALUES (
              :versionId, :definitionId, :version, 'DRAFT', :label, :description, :groupCode, :dataType,
              CAST(:validation AS jsonb), CAST(:applicability AS jsonb), :visibility,
              :requiredOnPublish, :filterable, :displayOrder, :unitLabel, :fingerprint, :actorId, :reason
            )
            """)
            .param("versionId", versionId).param("definitionId", definitionId).param("version", nextVersion)
            .param("label", required(input.label(), "label")).param("description", input.description())
            .param("groupCode", normalizeCode(input.groupCode())).param("dataType", upper(input.dataType()))
            .param("validation", json(input.validation())).param("applicability", json(input.applicability()))
            .param("visibility", upper(input.visibility())).param("requiredOnPublish", Boolean.TRUE.equals(input.requiredOnPublish()))
            .param("filterable", Boolean.TRUE.equals(input.filterable())).param("displayOrder", input.displayOrder() == null ? 0 : input.displayOrder())
            .param("unitLabel", input.unitLabel()).param("fingerprint", requestHash).param("actorId", actorId)
            .param("reason", requiredReason(input.reason())).update();
        if (input.options() != null) {
            int order = 0;
            for (var option : input.options()) {
                jdbc.sql("""
                    INSERT INTO property_attribute_options(definition_version_id, code, label, display_order)
                    VALUES (:versionId, :code, :label, :displayOrder)
                    """).param("versionId", versionId).param("code", normalizeCode(option.code()))
                    .param("label", required(option.label(), "option label"))
                    .param("displayOrder", option.displayOrder() == null ? order++ : option.displayOrder()).update();
            }
        }
        saveIdempotency(actorId, input.idempotencyKey(), requestHash, definitionId);
        audit(actorId, "PROPERTY_ATTRIBUTE_DRAFT_CREATED", definitionId, Map.of("version", nextVersion));
        return find(definitionId, true);
    }

    @Transactional
    public AttributeDefinition publish(UUID actorId, UUID definitionId, long expectedRowVersion, String reason) {
        var draft = jdbc.sql("""
            SELECT v.id, v.created_by_account_id, v.version
            FROM property_attribute_definition_versions v
            JOIN property_attribute_definitions d ON d.id = v.definition_id
            WHERE v.definition_id = :id AND v.status = 'DRAFT' AND d.row_version = :expected
            ORDER BY v.version DESC LIMIT 1 FOR UPDATE OF d, v
            """).param("id", definitionId).param("expected", expectedRowVersion)
            .query(Draft.class).optional().orElseThrow(() -> error("STALE_VERSION", "Draft or expected version was not found"));
        if (actorId.equals(draft.createdByAccountId())) {
            throw error("SELF_APPROVAL_FORBIDDEN", "The requester cannot publish their own attribute definition");
        }
        validatePublishable(draft.id());
        jdbc.sql("""
            UPDATE property_attribute_definition_versions
            SET status = 'RETIRED'
            WHERE definition_id = :definitionId AND status = 'PUBLISHED'
            """).param("definitionId", definitionId).update();
        jdbc.sql("""
            UPDATE property_attribute_definition_versions
            SET status = 'PUBLISHED', approved_by_account_id = :actorId,
                reason = :reason, published_at = now()
            WHERE id = :versionId AND status = 'DRAFT'
            """).param("actorId", actorId).param("reason", requiredReason(reason))
            .param("versionId", draft.id()).update();
        int updated = jdbc.sql("""
            UPDATE property_attribute_definitions SET row_version = row_version + 1, updated_at = now()
            WHERE id = :id AND row_version = :expected
            """).param("id", definitionId).param("expected", expectedRowVersion).update();
        if (updated != 1) throw error("STALE_VERSION", "Attribute definition changed concurrently");
        audit(actorId, "PROPERTY_ATTRIBUTE_PUBLISHED", definitionId, Map.of("version", draft.version()));
        outbox(definitionId, expectedRowVersion + 1, "vistay.property.attribute-definition-published",
            Map.of("definitionId", definitionId, "version", draft.version()));
        return find(definitionId, true);
    }

    @Transactional
    public AttributeDefinition retire(UUID actorId, UUID definitionId, long expectedRowVersion, String reason) {
        int updated = jdbc.sql("""
            UPDATE property_attribute_definitions SET active = false, row_version = row_version + 1, updated_at = now()
            WHERE id = :id AND row_version = :expected AND active
            """).param("id", definitionId).param("expected", expectedRowVersion).update();
        if (updated != 1) throw error("STALE_VERSION", "Attribute definition changed concurrently");
        audit(actorId, "PROPERTY_ATTRIBUTE_RETIRED", definitionId, Map.of("reason", requiredReason(reason)));
        outbox(definitionId, expectedRowVersion + 1, "vistay.property.attribute-definition-retired",
            Map.of("definitionId", definitionId));
        return find(definitionId, true);
    }

    @Transactional
    public PropertyAttributeValue setValue(UUID actorId, Set<String> scopes, PropertyAttributeValueInput input) {
        assertPropertyAccess(input.propertyId(), scopes, actorId);
        var definition = jdbc.sql("""
            SELECT d.id AS definition_id, v.id AS version_id, d.code, v.data_type
            FROM property_attribute_definitions d
            JOIN property_attribute_definition_versions v ON v.definition_id = d.id AND v.status = 'PUBLISHED'
            WHERE d.code = :code AND d.active
            """).param("code", normalizeCode(input.attributeCode())).query(ValueDefinition.class).optional()
            .orElseThrow(() -> error("ATTRIBUTE_NOT_FOUND", "Published attribute was not found"));
        Object canonical = canonicalValue(definition, input);
        String valueJson = json(canonical);
        Long current = jdbc.sql("""
            SELECT value_version FROM property_attribute_values
            WHERE property_id = :propertyId AND definition_id = :definitionId FOR UPDATE
            """).param("propertyId", input.propertyId()).param("definitionId", definition.definitionId())
            .query(Long.class).optional().orElse(null);
        long expected = input.expectedVersion() == null ? 0L : input.expectedVersion();
        if ((current == null ? 0 : current) != expected) throw error("STALE_VERSION", "Property attribute value changed concurrently");
        long next = expected + 1;
        jdbc.sql("""
            INSERT INTO property_attribute_values(property_id, definition_id, definition_version_id,
              value, value_version, updated_by_account_id)
            VALUES (:propertyId, :definitionId, :versionId, CAST(:value AS jsonb), :next, :actorId)
            ON CONFLICT (property_id, definition_id) DO UPDATE SET
              definition_version_id = EXCLUDED.definition_version_id, value = EXCLUDED.value,
              value_version = EXCLUDED.value_version, updated_by_account_id = EXCLUDED.updated_by_account_id,
              updated_at = now()
            """).param("propertyId", input.propertyId()).param("definitionId", definition.definitionId())
            .param("versionId", definition.versionId()).param("value", valueJson).param("next", next)
            .param("actorId", actorId).update();
        rebuildSearch(input.propertyId(), definition, canonical);
        audit(actorId, "PROPERTY_ATTRIBUTE_VALUE_SET", input.propertyId(), Map.of("attribute", definition.code()));
        return jdbc.sql("""
            SELECT d.code, v.label, v.data_type, v.visibility, value.value::text AS value_json,
                   value.value_version, value.updated_at
            FROM property_attribute_values value
            JOIN property_attribute_definitions d ON d.id = value.definition_id
            JOIN property_attribute_definition_versions v ON v.id = value.definition_version_id
            WHERE value.property_id = :propertyId AND value.definition_id = :definitionId
            """).param("propertyId", input.propertyId()).param("definitionId", definition.definitionId())
            .query(PropertyAttributeValue.class).single();
    }

    private void assertPropertyAccess(UUID propertyId, Set<String> scopes, UUID actorId) {
        if (scopes.contains("ALL")) return;
        if (scopes.contains("OWN")) {
            boolean owned = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM property_parties
                  WHERE property_id = :propertyId AND host_id = :actorId
                    AND relationship_type IN ('OWNER','CO_OWNER','REPRESENTATIVE')
                    AND valid_from <= current_date AND (valid_to IS NULL OR valid_to >= current_date))
                """).param("propertyId", propertyId).param("actorId", actorId).query(Boolean.class).single();
            if (owned) return;
        }
        throw error("FORBIDDEN_SCOPE", "The property is outside the actor's allowed scope");
    }

    private void validatePublishable(UUID versionId) {
        var type = jdbc.sql("SELECT data_type FROM property_attribute_definition_versions WHERE id=:id")
            .param("id", versionId).query(String.class).single();
        long options = jdbc.sql("SELECT count(*) FROM property_attribute_options WHERE definition_version_id=:id AND active")
            .param("id", versionId).query(Long.class).single();
        if ((type.endsWith("SELECT") && options < 1) || (!type.endsWith("SELECT") && options > 0)) {
            throw error("INVALID_ATTRIBUTE_OPTIONS", "Select attributes require options and other types cannot have options");
        }
    }

    private Object canonicalValue(ValueDefinition definition, PropertyAttributeValueInput input) {
        var supplied = new ArrayList<Object>();
        if (input.textValue() != null) supplied.add(input.textValue());
        if (input.integerValue() != null) supplied.add(input.integerValue());
        if (input.decimalValue() != null) supplied.add(input.decimalValue());
        if (input.booleanValue() != null) supplied.add(input.booleanValue());
        if (input.dateValue() != null) supplied.add(input.dateValue());
        if (input.optionCodes() != null) supplied.add(input.optionCodes());
        if (supplied.size() != 1) throw error("INVALID_ATTRIBUTE_VALUE", "Exactly one typed value must be supplied");
        Object value = switch (definition.dataType()) {
            case "TEXT", "LONG_TEXT" -> required(input.textValue(), "textValue");
            case "INTEGER" -> input.integerValue();
            case "DECIMAL" -> new BigDecimal(required(input.decimalValue(), "decimalValue"));
            case "BOOLEAN" -> input.booleanValue();
            case "DATE" -> LocalDate.parse(required(input.dateValue(), "dateValue")).toString();
            case "SINGLE_SELECT" -> {
                if (input.optionCodes() == null || input.optionCodes().size() != 1) throw error("INVALID_ATTRIBUTE_VALUE", "Single select requires one option");
                yield List.of(normalizeCode(input.optionCodes().getFirst()));
            }
            case "MULTI_SELECT" -> input.optionCodes() == null ? null : input.optionCodes().stream().map(PropertyAttributeService::normalizeCode).distinct().toList();
            default -> throw error("INVALID_ATTRIBUTE_TYPE", "Unsupported attribute type");
        };
        if (value == null) throw error("INVALID_ATTRIBUTE_VALUE", "Value does not match attribute type");
        if (definition.dataType().endsWith("SELECT")) validateOptions(definition.versionId(), (List<?>) value);
        return value;
    }

    private void validateOptions(UUID versionId, List<?> codes) {
        long valid = jdbc.sql("""
            SELECT count(*) FROM property_attribute_options
            WHERE definition_version_id = :versionId AND active AND code IN (:codes)
            """).param("versionId", versionId).param("codes", codes).query(Long.class).single();
        if (valid != codes.size()) throw error("INVALID_ATTRIBUTE_OPTION", "One or more options are not valid");
    }

    private void rebuildSearch(UUID propertyId, ValueDefinition definition, Object value) {
        jdbc.sql("DELETE FROM property_attribute_search_values WHERE property_id=:propertyId AND definition_id=:definitionId")
            .param("propertyId", propertyId).param("definitionId", definition.definitionId()).update();
        List<?> values = definition.dataType().endsWith("SELECT") ? (List<?>) value : List.of(value);
        int ordinal = 0;
        for (Object item : values) {
            var columns = new LinkedHashMap<String, Object>();
            switch (definition.dataType()) {
                case "TEXT", "LONG_TEXT" -> columns.put("text_value", item);
                case "INTEGER", "DECIMAL" -> columns.put("numeric_value", item);
                case "BOOLEAN" -> columns.put("boolean_value", item);
                case "DATE" -> columns.put("date_value", LocalDate.parse(item.toString()));
                default -> columns.put("option_code", item);
            }
            var column = columns.keySet().iterator().next();
            jdbc.sql("INSERT INTO property_attribute_search_values(property_id, definition_id, ordinal, " + column + ") VALUES (:p,:d,:o,:v)")
                .param("p", propertyId).param("d", definition.definitionId()).param("o", ordinal++).param("v", columns.get(column)).update();
        }
    }

    private AttributeDefinition find(UUID definitionId, boolean includeDrafts) {
        return catalog(includeDrafts).stream().filter(value -> value.id().equals(definitionId)).findFirst()
            .orElseThrow(() -> error("ATTRIBUTE_NOT_FOUND", "Attribute definition was not found"));
    }

    private void validateDraft(AttributeDraftInput input) {
        if (input == null || !CODE.matcher(normalizeCode(input.code())).matches()) throw error("INVALID_ATTRIBUTE_CODE", "Attribute code must be kebab-case");
        if (!TYPES.contains(upper(input.dataType()))) throw error("INVALID_ATTRIBUTE_TYPE", "Unsupported attribute type");
        if (!VISIBILITIES.contains(upper(input.visibility()))) throw error("INVALID_VISIBILITY", "Unsupported visibility");
        requiredReason(input.reason());
        required(input.idempotencyKey(), "idempotencyKey");
    }

    private UUID idempotent(UUID actorId, String key, String requestHash) {
        var row = jdbc.sql("SELECT request_hash, resource_id FROM property_attribute_idempotency WHERE actor_account_id=:actor AND idempotency_key=:key")
            .param("actor", actorId).param("key", key).query(Idempotency.class).optional().orElse(null);
        if (row == null) return null;
        if (!row.requestHash().equals(requestHash)) throw error("IDEMPOTENCY_CONFLICT", "Idempotency key was used with another payload");
        return row.resourceId();
    }

    private void saveIdempotency(UUID actorId, String key, String hash, UUID resourceId) {
        jdbc.sql("""
            INSERT INTO property_attribute_idempotency(actor_account_id,idempotency_key,request_hash,resource_id,response)
            VALUES (:actor,:key,:hash,:resource,CAST(:response AS jsonb))
            """).param("actor", actorId).param("key", key).param("hash", hash).param("resource", resourceId)
            .param("response", json(Map.of("definitionId", resourceId))).update();
    }

    private void audit(UUID actorId, String action, UUID aggregateId, Map<String, ?> changes) {
        jdbc.sql("""
            INSERT INTO audit_events(actor_admin_user_id,action,aggregate_type,aggregate_id,changes)
            VALUES (:actor,:action,'PROPERTY_ATTRIBUTE',:id,CAST(:changes AS jsonb))
            """).param("actor", actorId).param("action", action).param("id", aggregateId)
            .param("changes", json(changes)).update();
    }

    private void outbox(UUID aggregateId, long version, String type, Map<String, ?> payload) {
        jdbc.sql("""
            INSERT INTO outbox_events(aggregate_type,aggregate_id,aggregate_version,event_type,event_version,payload)
            VALUES ('PROPERTY_ATTRIBUTE',:id,:version,:type,1,CAST(:payload AS jsonb))
            """).param("id", aggregateId).param("version", version).param("type", type)
            .param("payload", json(payload)).update();
    }

    private String json(Object value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (RuntimeException exception) { throw error("INVALID_JSON", "Configuration could not be serialized"); }
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String normalizeCode(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String required(String value, String field) { if (value == null || value.isBlank()) throw error("INVALID_INPUT", field + " is required"); return value.trim(); }
    private static String requiredReason(String value) { var result = required(value, "reason"); if (result.length() < 8) throw error("INVALID_REASON", "Reason must contain at least 8 characters"); return result; }
    private static DynamicConfigException error(String code, String message) { return new DynamicConfigException(code, message); }

    public record AttributeDraftInput(String code, String label, String description, String groupCode,
        String dataType, Map<String, Object> validation, Map<String, Object> applicability,
        String visibility, Boolean requiredOnPublish, Boolean filterable, Integer displayOrder,
        String unitLabel, List<AttributeOptionInput> options, String reason, String idempotencyKey) {}
    public record AttributeOptionInput(String code, String label, Integer displayOrder) {}
    public record PropertyAttributeValueInput(UUID propertyId, String attributeCode, String textValue,
        Integer integerValue, String decimalValue, Boolean booleanValue, String dateValue,
        List<String> optionCodes, Long expectedVersion) {}
    public record AttributeDefinition(UUID id, String code, boolean active, long rowVersion,
        UUID versionId, int version, String status, String label, String description, String groupCode,
        String dataType, String validationJson, String applicabilityJson, String visibility,
        boolean requiredOnPublish, boolean filterable, int displayOrder, String unitLabel,
        UUID createdByAccountId, UUID approvedByAccountId, String reason) {}
    public record PropertyAttributeValue(String code, String label, String dataType, String visibility,
        String valueJson, long valueVersion, java.time.OffsetDateTime updatedAt) {}
    private record Draft(UUID id, UUID createdByAccountId, int version) {}
    private record ValueDefinition(UUID definitionId, UUID versionId, String code, String dataType) {}
    private record Idempotency(String requestHash, UUID resourceId) {}
}
