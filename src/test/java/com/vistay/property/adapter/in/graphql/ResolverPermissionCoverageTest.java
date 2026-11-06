package com.vistay.property.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;

class ResolverPermissionCoverageTest {
    @Test
    void everyGraphQlResolverIsPermissionPinnedOrExplicitlyPublic() {
        var controllers = List.of(PropertyAttributeController.class, ServiceInfoQuery.class);
        var uncovered = controllers.stream().flatMap(type ->
                java.util.Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(QueryMapping.class)
                        || method.isAnnotationPresent(MutationMapping.class))
                    .filter(method -> !method.isAnnotationPresent(RequiresPermission.class)
                        && !method.isAnnotationPresent(PublicResolver.class))
                    .map(method -> type.getSimpleName() + "." + method.getName()))
            .toList();
        assertThat(uncovered).as("unprotected GraphQL resolvers").isEmpty();
    }
}
