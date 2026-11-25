package com.vistay.property.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OperatingBranchMutationContractTest {
    @Test
    void branchCanBeCreatedBeforeTheFirstPropertyMembership() throws Exception {
        var source = Files.readString(Path.of(
            "src/main/java/com/vistay/property/adapter/in/graphql/PropertyPortfolioController.java"));
        var schema = Files.readString(Path.of("src/main/resources/graphql/schema.graphqls"));

        assertThat(source).contains(
            "public OperatingBranch createOperatingBranch",
            "@RequiresPermission(\"property:create\")",
            "INSERT INTO operating_branches(id, code, name)",
            "property.branch.created"
        );
        assertThat(schema).contains(
            "createOperatingBranch(input: CreateOperatingBranchInput!): OperatingBranch!",
            "input CreateOperatingBranchInput { code: String!, name: String! }"
        );
    }
}
