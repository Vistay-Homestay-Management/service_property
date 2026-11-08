package com.vistay.property.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PropertyOperationalSummaryContractTest {
    @Test
    void projectionIsPropertyOwnedPermissionPinnedAndAllScoped() throws Exception {
        var source = Files.readString(Path.of(
            "src/main/java/com/vistay/property/adapter/in/graphql/PropertyOperationalSummaryController.java"));
        var schema = Files.readString(Path.of("src/main/resources/graphql/schema.graphqls"));
        assertThat(source).contains("@RequiresPermission(ANALYTICS_READ)",
            "System property overview requires ALL scope", "FROM properties p", "LEFT JOIN unit_assets");
        assertThat(schema).contains("propertyOperationalSummary", "PropertyOperationalSummary");
    }
}
