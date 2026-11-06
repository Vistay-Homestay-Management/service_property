package com.vistay.property;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PropertyAttributeMigrationContractTest {
    @Test
    void definesTypedCatalogSearchProjectionAndImmutableHistory() throws Exception {
        var catalog = Files.readString(Path.of("src/main/resources/db/migration/V2_1__property_attribute_catalog.sql"));
        var history = Files.readString(Path.of("src/main/resources/db/migration/V2_2__property_attribute_history.sql"));
        assertThat(catalog).contains("property_attribute_definition_versions",
            "TEXT', 'LONG_TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'SINGLE_SELECT', 'MULTI_SELECT",
            "property_attribute_search_values", "num_nonnulls", "value_version");
        assertThat(history).contains("published property attribute versions are immutable",
            "must be retired, not deleted");
    }
}
