package com.vistay.property.adapter.in.graphql;

import com.vistay.property.application.PropertyAttributeService;
import com.vistay.property.application.PropertyAttributeService.AttributeDefinition;
import com.vistay.property.application.PropertyAttributeService.AttributeDraftInput;
import com.vistay.property.application.PropertyAttributeService.PropertyAttributeValue;
import com.vistay.property.application.PropertyAttributeService.PropertyAttributeValueInput;
import java.util.List;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

@Controller
public class PropertyAttributeController {
    private final PropertyAttributeService attributes;
    private final PropertyAuthorizationContext authorization;

    public PropertyAttributeController(PropertyAttributeService attributes,
        PropertyAuthorizationContext authorization) {
        this.attributes = attributes;
        this.authorization = authorization;
    }

    @QueryMapping
    @RequiresPermission("property-attribute:read")
    public List<AttributeDefinition> propertyAttributeCatalog(@AuthenticationPrincipal Jwt jwt,
        @Argument boolean includeDrafts) {
        authorization.require(jwt, "property-attribute:read");
        if (includeDrafts) requireAll(jwt, "property-attribute:manage");
        return attributes.catalog(includeDrafts);
    }

    @QueryMapping
    @RequiresPermission("property-attribute:read")
    public List<PropertyAttributeValue> propertyAttributeValues(@AuthenticationPrincipal Jwt jwt,
        @Argument UUID propertyId) {
        var scopes = authorization.require(jwt, "property-attribute:read");
        return attributes.values(propertyId, scopes, actor(jwt));
    }

    @MutationMapping
    @RequiresPermission("property-attribute:manage")
    public AttributeDefinition createPropertyAttributeDraft(@AuthenticationPrincipal Jwt jwt,
        @Argument AttributeDraftInput input) {
        requireAll(jwt, "property-attribute:manage");
        return attributes.createDraft(actor(jwt), input);
    }

    @MutationMapping
    @RequiresPermission("property-attribute:publish")
    public AttributeDefinition publishPropertyAttribute(@AuthenticationPrincipal Jwt jwt,
        @Argument UUID definitionId, @Argument String expectedRowVersion, @Argument String reason) {
        requireAll(jwt, "property-attribute:publish");
        return attributes.publish(actor(jwt), definitionId, Long.parseLong(expectedRowVersion), reason);
    }

    @MutationMapping
    @RequiresPermission("property-attribute:publish")
    public AttributeDefinition retirePropertyAttribute(@AuthenticationPrincipal Jwt jwt,
        @Argument UUID definitionId, @Argument String expectedRowVersion, @Argument String reason) {
        requireAll(jwt, "property-attribute:publish");
        return attributes.retire(actor(jwt), definitionId, Long.parseLong(expectedRowVersion), reason);
    }

    @MutationMapping
    @RequiresPermission("property-attribute:manage")
    public PropertyAttributeValue setPropertyAttributeValue(@AuthenticationPrincipal Jwt jwt,
        @Argument PropertyAttributeValueInput input) {
        var scopes = authorization.require(jwt, "property-attribute:manage");
        return attributes.setValue(actor(jwt), scopes, input);
    }

    private static UUID actor(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("JWT sub must be a UUID"); }
    }

    private void requireAll(Jwt jwt, String permission) {
        if (!authorization.require(jwt, permission).contains("ALL")) {
            throw new com.vistay.property.application.DynamicConfigException(
                "FORBIDDEN_SCOPE", permission + " requires ALL scope for catalog governance");
        }
    }
}
