package com.vistay.property.adapter.in.graphql;

import graphql.GraphqlErrorException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

@Controller
public class PropertyPortfolioController {
    private static final List<String> OPERATING_MODELS = List.of("OWNED", "LEASED_BY_VISTAY", "MANAGEMENT");
    private static final List<String> CONTRACT_STATUSES = List.of("DRAFT", "ACTIVE", "EXPIRING", "RENEWING");
    private final JdbcClient jdbc;

    public PropertyPortfolioController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @QueryMapping
    @RequiresPermission("property:read")
    public PropertyPortfolio propertyPortfolio(@Argument String search, @Argument String operatingModel) {
        var query = search == null ? "" : search.trim().toLowerCase();
        var model = operatingModel == null ? "" : operatingModel.trim().toUpperCase();
        if (!model.isBlank() && !OPERATING_MODELS.contains(model)) throw bad("INVALID_OPERATING_MODEL", "Operating model is invalid");
        var branches = jdbc.sql("SELECT id, code, name FROM operating_branches WHERE active ORDER BY name")
            .query(OperatingBranch.class).list();
        var rows = jdbc.sql("""
                SELECT c.id city_id, c.code city_code, c.name city_name,
                       a.id area_id, a.code area_code, a.name area_name,
                       b.id building_id, b.code building_code, b.name building_name, b.address_line,
                       f.id floor_id, f.code floor_code, f.name floor_name, f.floor_number,
                       p.id property_id, p.code property_code, p.unit_number, p.name property_name,
                       p.operating_model, p.operational_status, p.area_sqm, p.bedroom_count,
                       p.bathroom_count, p.capacity, p.base_price_per_night, p.currency, p.version,
                       membership.branch_id, party.host_id
                FROM cities c
                LEFT JOIN areas a ON a.city_id = c.id AND a.active
                LEFT JOIN buildings b ON b.area_id = a.id AND b.active
                LEFT JOIN building_floors f ON f.building_id = b.id AND f.active
                LEFT JOIN properties p ON p.floor_id = f.id AND p.active
                LEFT JOIN LATERAL (
                    SELECT pbm.branch_id FROM property_branch_memberships pbm
                    WHERE pbm.property_id = p.id AND pbm.effective_to IS NULL
                    ORDER BY pbm.membership_version DESC LIMIT 1
                ) membership ON true
                LEFT JOIN LATERAL (
                    SELECT pp.host_id FROM property_parties pp
                    WHERE pp.property_id = p.id AND pp.valid_from <= current_date
                      AND (pp.valid_to IS NULL OR pp.valid_to >= current_date)
                    ORDER BY (pp.relationship_type = 'OWNER') DESC, pp.valid_from DESC LIMIT 1
                ) party ON true
                WHERE c.active
                  AND (:model = '' OR p.operating_model = :model)
                  AND (:search = '' OR lower(concat_ws(' ', c.code, c.name, a.code, a.name,
                      b.code, b.name, b.address_line, f.code, f.name, p.code, p.unit_number, p.name)) LIKE '%' || :search || '%')
                ORDER BY c.name, a.name, b.name, f.sort_order, f.floor_number NULLS LAST, p.unit_number, p.name
                """)
            .param("model", model).param("search", query)
            .query((rs, row) -> new FlatRow(
                rs.getObject("city_id", UUID.class), rs.getString("city_code"), rs.getString("city_name"),
                rs.getObject("area_id", UUID.class), rs.getString("area_code"), rs.getString("area_name"),
                rs.getObject("building_id", UUID.class), rs.getString("building_code"), rs.getString("building_name"), rs.getString("address_line"),
                rs.getObject("floor_id", UUID.class), rs.getString("floor_code"), rs.getString("floor_name"), (Integer) rs.getObject("floor_number"),
                rs.getObject("property_id", UUID.class), rs.getString("property_code"), rs.getString("unit_number"), rs.getString("property_name"),
                rs.getString("operating_model"), rs.getString("operational_status"), rs.getBigDecimal("area_sqm"),
                rs.getObject("bedroom_count") == null ? null : rs.getInt("bedroom_count"),
                rs.getObject("bathroom_count") == null ? null : rs.getInt("bathroom_count"),
                rs.getObject("capacity") == null ? null : rs.getInt("capacity"), rs.getBigDecimal("base_price_per_night"),
                rs.getString("currency"), rs.getObject("branch_id", UUID.class), rs.getObject("host_id", UUID.class),
                rs.getObject("version") == null ? null : rs.getLong("version"))).list();
        return new PropertyPortfolio(branches, portfolio(rows));
    }

    @QueryMapping
    @RequiresPermission("contract:read")
    public List<OwnerContract> ownerContracts(@Argument UUID propertyId, @Argument String status) {
        var normalized = status == null ? "" : status.trim().toUpperCase();
        var sql = new StringBuilder("""
                SELECT DISTINCT oc.id, oc.contract_number, oc.start_date, oc.end_date, oc.status, oc.notes, oc.version
                FROM owner_contracts oc
                JOIN contract_property_terms term ON term.contract_id = oc.id
                WHERE 1 = 1
                """);
        if (propertyId != null) sql.append(" AND term.property_id = :propertyId");
        if (!normalized.isBlank()) sql.append(" AND oc.status = :status");
        sql.append(" ORDER BY oc.start_date DESC, oc.contract_number");

        var statement = jdbc.sql(sql.toString());
        if (propertyId != null) statement = statement.param("propertyId", propertyId);
        if (!normalized.isBlank()) statement = statement.param("status", normalized);
        var contracts = statement.query((rs, row) -> new ContractBase(rs.getObject("id", UUID.class), rs.getString("contract_number"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class), rs.getString("status"),
                rs.getString("notes"), rs.getLong("version"))).list();
        return contracts.stream().map(this::contract).toList();
    }

    @MutationMapping @Transactional @RequiresPermission("property:create")
    public PropertyCity createCity(@AuthenticationPrincipal Jwt jwt, @Argument CreateCityInput input) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO cities(id, code, name) VALUES (:id, :code, :name)")
            .param("id", id).param("code", required(input.code(), "code").toUpperCase()).param("name", required(input.name(), "name")).update();
        audit(actor(jwt), "property.city.created", "CITY", id);
        return new PropertyCity(id, input.code().trim().toUpperCase(), input.name().trim(), List.of());
    }

    @MutationMapping @Transactional @RequiresPermission("property:create")
    public PropertyArea createArea(@Argument CreateAreaInput input) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO areas(id, city_id, code, name) VALUES (:id, :cityId, :code, :name)")
            .param("id", id).param("cityId", input.cityId()).param("code", required(input.code(), "code").toUpperCase())
            .param("name", required(input.name(), "name")).update();
        return new PropertyArea(id, input.cityId(), input.code().trim().toUpperCase(), input.name().trim(), List.of());
    }

    @MutationMapping @Transactional @RequiresPermission("property:create")
    public PropertyBuilding createBuilding(@Argument CreateBuildingInput input) {
        var id = UUID.randomUUID();
        jdbc.sql("INSERT INTO buildings(id, area_id, code, name, address_line) VALUES (:id, :areaId, :code, :name, :address)")
            .param("id", id).param("areaId", input.areaId()).param("code", required(input.code(), "code").toUpperCase())
            .param("name", required(input.name(), "name")).param("address", required(input.address(), "address")).update();
        return new PropertyBuilding(id, input.areaId(), input.code().trim().toUpperCase(), input.name().trim(), input.address().trim(), List.of());
    }

    @MutationMapping @Transactional @RequiresPermission("property:create")
    public PropertyFloor createBuildingFloor(@Argument CreateBuildingFloorInput input) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO building_floors(id, building_id, code, name, floor_number, sort_order)
                VALUES (:id, :buildingId, :code, :name, :floorNumber, :sortOrder)
                """).param("id", id).param("buildingId", input.buildingId()).param("code", required(input.code(), "code").toUpperCase())
            .param("name", required(input.name(), "name")).param("floorNumber", input.floorNumber())
            .param("sortOrder", input.sortOrder() == null ? 0 : input.sortOrder()).update();
        return new PropertyFloor(id, input.buildingId(), input.code().trim().toUpperCase(), input.name().trim(), input.floorNumber(), List.of());
    }

    @MutationMapping @Transactional @RequiresPermission("property:create")
    public PropertyUnit createPropertyUnit(@AuthenticationPrincipal Jwt jwt, @Argument CreatePropertyUnitInput input) {
        var model = required(input.operatingModel(), "operatingModel").toUpperCase();
        if (!OPERATING_MODELS.contains(model)) throw bad("INVALID_OPERATING_MODEL", "Operating model is invalid");
        var id = UUID.randomUUID();
        var actor = actor(jwt);
        jdbc.sql("""
                INSERT INTO properties(id, floor_id, code, unit_number, name, operating_model, area_sqm,
                  bedroom_count, bathroom_count, capacity, base_price_per_night, currency)
                VALUES (:id, :floorId, :code, :unitNumber, :name, :model, :areaSqm,
                  :bedrooms, :bathrooms, :capacity, :basePrice, :currency)
                """).param("id", id).param("floorId", input.floorId()).param("code", required(input.code(), "code").toUpperCase())
            .param("unitNumber", blankToNull(input.unitNumber())).param("name", required(input.name(), "name")).param("model", model)
            .param("areaSqm", decimal(input.areaSqm())).param("bedrooms", nonNegative(input.bedroomCount()))
            .param("bathrooms", nonNegative(input.bathroomCount())).param("capacity", Math.max(1, input.capacity() == null ? 1 : input.capacity()))
            .param("basePrice", decimal(input.basePricePerNight())).param("currency", currency(input.currency())).update();
        jdbc.sql("""
                INSERT INTO property_branch_memberships(property_id, branch_id, membership_version, effective_from, assigned_by_admin_user_id, reason)
                VALUES (:propertyId, :branchId, 1, now(), :actorId, 'Initial property branch assignment')
                """).param("propertyId", id).param("branchId", input.branchId()).param("actorId", actor).update();
        audit(actor, "property.created", "PROPERTY", id);
        outbox("PROPERTY", id, "property.created.v1", 0);
        return new PropertyUnit(id, input.floorId(), input.branchId(), input.code().trim().toUpperCase(), blankToNull(input.unitNumber()),
            input.name().trim(), model, "ONBOARDING", text(decimal(input.areaSqm())), nonNegative(input.bedroomCount()),
            nonNegative(input.bathroomCount()), Math.max(1, input.capacity() == null ? 1 : input.capacity()),
            text(decimal(input.basePricePerNight())), currency(input.currency()), null, "0");
    }

    @MutationMapping @Transactional @RequiresPermission("contract:create")
    public OwnerContract createOwnerContract(@AuthenticationPrincipal Jwt jwt, @Argument CreateOwnerContractInput input) {
        var model = required(input.operatingModel(), "operatingModel").toUpperCase();
        var status = input.status() == null ? "ACTIVE" : input.status().trim().toUpperCase();
        if (!OPERATING_MODELS.contains(model)) throw bad("INVALID_OPERATING_MODEL", "Operating model is invalid");
        if (!CONTRACT_STATUSES.contains(status)) throw bad("INVALID_CONTRACT_STATUS", "Contract status is invalid");
        if (!input.endDate().isAfter(input.startDate())) throw bad("INVALID_CONTRACT_DATES", "Contract end date must be after start date");
        var fixed = decimal(input.fixedAmount());
        var fee = decimal(input.managementFeePercent());
        if ("LEASED_BY_VISTAY".equals(model) && fixed == null) throw bad("FIXED_AMOUNT_REQUIRED", "Fixed amount is required for leased property");
        if ("MANAGEMENT".equals(model) && fee == null) throw bad("MANAGEMENT_FEE_REQUIRED", "Management fee is required for managed property");
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO owner_contracts(id, contract_number, start_date, end_date, status, notes)
                VALUES (:id, :number, :startDate, :endDate, :status, :notes)
                """).param("id", id).param("number", required(input.contractNumber(), "contractNumber").toUpperCase())
            .param("startDate", input.startDate()).param("endDate", input.endDate()).param("status", status)
            .param("notes", blankToNull(input.notes())).update();
        jdbc.sql("""
                INSERT INTO owner_contract_parties(contract_id, host_id, party_role, ownership_percent, is_primary)
                VALUES (:contractId, :hostId, 'OWNER', 100, true)
                """).param("contractId", id).param("hostId", input.hostProfileId()).update();
        jdbc.sql("""
                INSERT INTO contract_property_terms(contract_id, property_id, operating_model, effective_from, effective_to,
                  fixed_amount, management_fee_percent, payment_period, payment_cycle_count, currency, status)
                VALUES (:contractId, :propertyId, :model, :startDate, :endDate, :fixed, :fee, :period, :cycle, :currency, :status)
                """).param("contractId", id).param("propertyId", input.propertyId()).param("model", model)
            .param("startDate", input.startDate()).param("endDate", input.endDate()).param("fixed", fixed).param("fee", fee)
            .param("period", input.paymentPeriod() == null ? "MONTH" : input.paymentPeriod().trim().toUpperCase())
            .param("cycle", Math.max(1, input.paymentCycleCount() == null ? 1 : input.paymentCycleCount()))
            .param("currency", currency(input.currency())).param("status", status).update();
        jdbc.sql("""
                INSERT INTO property_parties(property_id, host_id, relationship_type, ownership_percentage, valid_from, valid_to, notes)
                VALUES (:propertyId, :hostId, 'OWNER', 100, :startDate, :endDate, :notes)
                """).param("propertyId", input.propertyId()).param("hostId", input.hostProfileId())
            .param("startDate", input.startDate()).param("endDate", input.endDate()).param("notes", "Created from " + input.contractNumber()).update();
        jdbc.sql("UPDATE properties SET operating_model = :model, version = version + 1, updated_at = now() WHERE id = :propertyId")
            .param("model", model).param("propertyId", input.propertyId()).update();
        audit(actor(jwt), "owner-contract.created", "OWNER_CONTRACT", id);
        outbox("OWNER_CONTRACT", id, "property.owner-contract.created.v1", 0);
        return contract(new ContractBase(id, input.contractNumber().trim().toUpperCase(), input.startDate(), input.endDate(), status, blankToNull(input.notes()), 0));
    }

    private List<PropertyCity> portfolio(List<FlatRow> rows) {
        var cities = new LinkedHashMap<UUID, CityBuilder>();
        for (var row : rows) {
            var city = cities.computeIfAbsent(row.cityId(), ignored -> new CityBuilder(row.cityId(), row.cityCode(), row.cityName()));
            if (row.areaId() == null) continue;
            var area = city.areas.computeIfAbsent(row.areaId(), ignored -> new AreaBuilder(row.areaId(), row.areaCode(), row.areaName()));
            if (row.buildingId() == null) continue;
            var building = area.buildings.computeIfAbsent(row.buildingId(), ignored -> new BuildingBuilder(row.buildingId(), row.buildingCode(), row.buildingName(), row.address()));
            if (row.floorId() == null) continue;
            var floor = building.floors.computeIfAbsent(row.floorId(), ignored -> new FloorBuilder(row.floorId(), row.floorCode(), row.floorName(), row.floorNumber()));
            if (row.propertyId() != null) floor.units.add(new PropertyUnit(row.propertyId(), row.floorId(), row.branchId(), row.propertyCode(), row.unitNumber(),
                row.propertyName(), row.operatingModel(), row.operationalStatus(), text(row.areaSqm()), row.bedroomCount(), row.bathroomCount(),
                row.capacity(), text(row.basePrice()), row.currency() == null ? "VND" : row.currency().trim(), row.hostId(), Long.toString(row.version())));
        }
        return cities.values().stream().map(CityBuilder::view).toList();
    }

    private OwnerContract contract(ContractBase base) {
        var parties = jdbc.sql("SELECT host_id, party_role, ownership_percent, is_primary FROM owner_contract_parties WHERE contract_id = :id ORDER BY is_primary DESC")
            .param("id", base.id()).query((rs, row) -> new OwnerContractParty(rs.getObject("host_id", UUID.class), rs.getString("party_role"),
                text(rs.getBigDecimal("ownership_percent")), rs.getBoolean("is_primary"))).list();
        var terms = jdbc.sql("""
                SELECT property_id, operating_model, effective_from, effective_to, fixed_amount, management_fee_percent,
                       payment_period, payment_cycle_count, currency, status
                FROM contract_property_terms WHERE contract_id = :id ORDER BY effective_from
                """).param("id", base.id()).query((rs, row) -> new OwnerContractPropertyTerm(rs.getObject("property_id", UUID.class),
                rs.getString("operating_model"), rs.getObject("effective_from", LocalDate.class).toString(),
                rs.getObject("effective_to", LocalDate.class).toString(), text(rs.getBigDecimal("fixed_amount")),
                text(rs.getBigDecimal("management_fee_percent")), rs.getString("payment_period"), rs.getInt("payment_cycle_count"),
                rs.getString("currency").trim(), rs.getString("status"))).list();
        return new OwnerContract(base.id(), base.number(), base.startDate().toString(), base.endDate().toString(), base.status(), base.notes(), Long.toString(base.version()), parties, terms);
    }

    private void audit(UUID actor, String action, String type, UUID id) {
        jdbc.sql("INSERT INTO audit_events(actor_admin_user_id, action, aggregate_type, aggregate_id) VALUES (:actor, :action, :type, :id)")
            .param("actor", actor).param("action", action).param("type", type).param("id", id).update();
    }

    private void outbox(String type, UUID id, String event, long version) {
        jdbc.sql("""
                INSERT INTO outbox_events(aggregate_type, aggregate_id, aggregate_version, event_type, payload)
                VALUES (:type, :id, :version, :event, jsonb_build_object('id', CAST(:idText AS text)))
                """).param("type", type).param("id", id).param("version", version).param("event", event).param("idText", id.toString()).update();
    }

    private static UUID actor(Jwt jwt) {
        if (jwt == null) return null;
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException invalid) { throw bad("INVALID_TOKEN", "Token subject is invalid"); }
    }
    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) throw bad("VALIDATION_ERROR", field + " is required");
        return value.trim();
    }
    private static String blankToNull(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private static BigDecimal decimal(String value) { return value == null || value.isBlank() ? null : new BigDecimal(value.trim()); }
    private static String text(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    private static int nonNegative(Integer value) { return Math.max(0, value == null ? 0 : value); }
    private static String currency(String value) {
        var result = value == null || value.isBlank() ? "VND" : value.trim().toUpperCase();
        if (!result.matches("[A-Z]{3}")) throw bad("INVALID_CURRENCY", "Currency must be a three-letter code");
        return result;
    }
    private static GraphqlErrorException bad(String code, String message) {
        return GraphqlErrorException.newErrorException().message(message).errorClassification(ErrorType.BAD_REQUEST).extensions(Map.of("code", code)).build();
    }

    private record FlatRow(UUID cityId, String cityCode, String cityName, UUID areaId, String areaCode, String areaName,
        UUID buildingId, String buildingCode, String buildingName, String address, UUID floorId, String floorCode, String floorName,
        Integer floorNumber, UUID propertyId, String propertyCode, String unitNumber, String propertyName, String operatingModel,
        String operationalStatus, BigDecimal areaSqm, Integer bedroomCount, Integer bathroomCount, Integer capacity,
        BigDecimal basePrice, String currency, UUID branchId, UUID hostId, Long version) {}
    private record ContractBase(UUID id, String number, LocalDate startDate, LocalDate endDate, String status, String notes, long version) {}
    private static final class CityBuilder {
        final UUID id; final String code; final String name; final Map<UUID, AreaBuilder> areas = new LinkedHashMap<>();
        CityBuilder(UUID id, String code, String name) { this.id=id; this.code=code; this.name=name; }
        PropertyCity view() { return new PropertyCity(id, code, name, areas.values().stream().map(a -> a.view(id)).toList()); }
    }
    private static final class AreaBuilder {
        final UUID id; final String code; final String name; final Map<UUID, BuildingBuilder> buildings = new LinkedHashMap<>();
        AreaBuilder(UUID id, String code, String name) { this.id=id; this.code=code; this.name=name; }
        PropertyArea view(UUID cityId) { return new PropertyArea(id, cityId, code, name, buildings.values().stream().map(b -> b.view(id)).toList()); }
    }
    private static final class BuildingBuilder {
        final UUID id; final String code; final String name; final String address; final Map<UUID, FloorBuilder> floors = new LinkedHashMap<>();
        BuildingBuilder(UUID id, String code, String name, String address) { this.id=id; this.code=code; this.name=name; this.address=address; }
        PropertyBuilding view(UUID areaId) { return new PropertyBuilding(id, areaId, code, name, address, floors.values().stream().map(f -> f.view(id)).toList()); }
    }
    private static final class FloorBuilder {
        final UUID id; final String code; final String name; final Integer number; final List<PropertyUnit> units = new ArrayList<>();
        FloorBuilder(UUID id, String code, String name, Integer number) { this.id=id; this.code=code; this.name=name; this.number=number; }
        PropertyFloor view(UUID buildingId) { return new PropertyFloor(id, buildingId, code, name, number, List.copyOf(units)); }
    }

    public record PropertyPortfolio(List<OperatingBranch> branches, List<PropertyCity> cities) {}
    public record OperatingBranch(UUID id, String code, String name) {}
    public record PropertyCity(UUID id, String code, String name, List<PropertyArea> areas) {}
    public record PropertyArea(UUID id, UUID cityId, String code, String name, List<PropertyBuilding> buildings) {}
    public record PropertyBuilding(UUID id, UUID areaId, String code, String name, String address, List<PropertyFloor> floors) {}
    public record PropertyFloor(UUID id, UUID buildingId, String code, String name, Integer floorNumber, List<PropertyUnit> units) {}
    public record PropertyUnit(UUID id, UUID floorId, UUID branchId, String code, String unitNumber, String name, String operatingModel,
        String operationalStatus, String areaSqm, int bedroomCount, int bathroomCount, int capacity, String basePricePerNight,
        String currency, UUID hostProfileId, String version) {}
    public record OwnerContract(UUID id, String contractNumber, String startDate, String endDate, String status, String notes, String version,
        List<OwnerContractParty> parties, List<OwnerContractPropertyTerm> propertyTerms) {}
    public record OwnerContractParty(UUID hostProfileId, String partyRole, String ownershipPercent, boolean primary) {}
    public record OwnerContractPropertyTerm(UUID propertyId, String operatingModel, String effectiveFrom, String effectiveTo,
        String fixedAmount, String managementFeePercent, String paymentPeriod, int paymentCycleCount, String currency, String status) {}
    public record CreateCityInput(String code, String name) {}
    public record CreateAreaInput(UUID cityId, String code, String name) {}
    public record CreateBuildingInput(UUID areaId, String code, String name, String address) {}
    public record CreateBuildingFloorInput(UUID buildingId, String code, String name, Integer floorNumber, Integer sortOrder) {}
    public record CreatePropertyUnitInput(UUID floorId, UUID branchId, String code, String unitNumber, String name, String operatingModel,
        String areaSqm, Integer bedroomCount, Integer bathroomCount, Integer capacity, String basePricePerNight, String currency) {}
    public record CreateOwnerContractInput(String contractNumber, UUID propertyId, UUID hostProfileId, String operatingModel,
        LocalDate startDate, LocalDate endDate, String fixedAmount, String managementFeePercent, String paymentPeriod,
        Integer paymentCycleCount, String currency, String status, String notes) {}
}
