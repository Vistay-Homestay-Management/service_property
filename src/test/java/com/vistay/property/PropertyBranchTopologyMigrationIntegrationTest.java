package com.vistay.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class PropertyBranchTopologyMigrationIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("vistay_property_test")
            .withUsername("vistay")
            .withPassword("vistay");

    @BeforeAll
    static void migrate() {
        POSTGRES.start();
        var result = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("app")
            .defaultSchema("app")
            .load()
            .migrate();
        assertThat(result.success).isTrue();
        assertThat(result.targetSchemaVersion).isEqualTo("3");
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void enforcesVersionedNonOverlappingPropertyMemberships() throws Exception {
        try (var connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("SET search_path TO app, extensions, public");
            statement.execute("""
                INSERT INTO cities(id, code, name)
                VALUES ('00000000-0000-0000-0000-000000000001', 'HCM', 'Ho Chi Minh City');
                INSERT INTO areas(id, city_id, code, name)
                VALUES ('00000000-0000-0000-0000-000000000002',
                        '00000000-0000-0000-0000-000000000001', 'D1', 'District 1');
                INSERT INTO buildings(id, area_id, code, name, address_line)
                VALUES ('00000000-0000-0000-0000-000000000003',
                        '00000000-0000-0000-0000-000000000002', 'BLD-1', 'Building 1', '1 Test Street');
                INSERT INTO properties(id, building_id, code, name, operating_model)
                VALUES ('00000000-0000-0000-0000-000000000004',
                        '00000000-0000-0000-0000-000000000003', 'PROP-1', 'Property 1', 'OWNED');
                INSERT INTO operating_branches(id, code, name)
                VALUES ('00000000-0000-0000-0000-000000000005', 'BR-1', 'Branch 1');
                INSERT INTO property_branch_memberships(
                    property_id, branch_id, membership_version, effective_from, effective_to, reason)
                VALUES ('00000000-0000-0000-0000-000000000004',
                        '00000000-0000-0000-0000-000000000005', 1,
                        '2026-01-01T00:00:00Z', '2026-03-01T00:00:00Z', 'Initial branch assignment');
                """);

            assertThatThrownBy(() -> statement.execute("""
                INSERT INTO property_branch_memberships(
                    property_id, branch_id, membership_version, effective_from, effective_to, reason)
                VALUES ('00000000-0000-0000-0000-000000000004',
                        '00000000-0000-0000-0000-000000000005', 2,
                        '2026-02-01T00:00:00Z', '2026-04-01T00:00:00Z', 'Overlapping assignment');
                """))
                .isInstanceOf(SQLException.class)
                .extracting(error -> ((SQLException) error).getSQLState())
                .isEqualTo("23P01");

            statement.execute("""
                INSERT INTO property_branch_memberships(
                    property_id, branch_id, membership_version, effective_from, reason)
                VALUES ('00000000-0000-0000-0000-000000000004',
                        '00000000-0000-0000-0000-000000000005', 2,
                        '2026-03-01T00:00:00Z', 'Non-overlapping reassignment');
                """);
        }
    }
}
