package com.vistay.property.adapter.in.graphql;

import graphql.GraphqlErrorException;
import java.util.Map;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

/** Property-owned counts for the federated Admin overview. */
@Controller
public class PropertyOperationalSummaryController {
    private static final String ANALYTICS_READ = "analytics:read";
    private final JdbcClient jdbc;
    private final PropertyAuthorizationContext authorization;

    public PropertyOperationalSummaryController(JdbcClient jdbc,
        PropertyAuthorizationContext authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    @QueryMapping
    @RequiresPermission(ANALYTICS_READ)
    public PropertyOperationalSummary propertyOperationalSummary(
        @AuthenticationPrincipal Jwt jwt) {
        var scopes = authorization.require(jwt, ANALYTICS_READ);
        if (!scopes.contains("ALL")) {
            throw GraphqlErrorException.newErrorException()
                .message("System property overview requires ALL scope")
                .errorClassification(ErrorType.FORBIDDEN)
                .extensions(Map.of("code", "FORBIDDEN_ANALYTICS_SCOPE")).build();
        }
        return jdbc.sql("""
            SELECT
              COUNT(DISTINCT p.id) FILTER (WHERE p.active AND p.operational_status = 'ACTIVE') AS active_properties,
              COUNT(DISTINCT p.id) FILTER (WHERE p.active AND p.operational_status = 'MAINTENANCE') AS maintenance_properties,
              COUNT(DISTINCT p.id) FILTER (WHERE a.lifecycle_status IN ('IN_REPAIR','MISSING'))
                AS properties_needing_attention
            FROM properties p
            LEFT JOIN unit_assets a ON a.property_id = p.id
            """).query(PropertyOperationalSummary.class).single();
    }

    public record PropertyOperationalSummary(long activeProperties, long maintenanceProperties,
        long propertiesNeedingAttention) {}
}
