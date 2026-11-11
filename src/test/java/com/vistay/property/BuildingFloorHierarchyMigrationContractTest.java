package com.vistay.property;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BuildingFloorHierarchyMigrationContractTest {
    @Test
    void normalizesFloorsAndRemovesFixedPropertyClassification() throws Exception {
        var migration = Files.readString(Path.of(
            "src/main/resources/db/migration/V7__building_floor_hierarchy.sql"));

        assertThat(migration)
            .contains("CREATE TABLE building_floors", "ADD COLUMN floor_id uuid")
            .contains("FOREIGN KEY (floor_id) REFERENCES building_floors(id)")
            .contains("DROP COLUMN floor_label", "DROP COLUMN property_type", "DROP COLUMN building_id")
            .contains("applicability - 'propertyTypes'");
    }
}
