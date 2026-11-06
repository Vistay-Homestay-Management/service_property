package com.vistay.property.adapter.in.graphql;

import com.vistay.contracts.common.v1.AccountType;
import com.vistay.contracts.common.v1.DataScopeType;
import com.vistay.contracts.user.v1.GetRolePoliciesRequest;
import com.vistay.contracts.user.v1.UserAuthorizationServiceGrpc;
import graphql.GraphqlErrorException;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class PropertyAuthorizationContext {
    private final StringRedisTemplate redis;
    private final UserAuthorizationServiceGrpc.UserAuthorizationServiceBlockingStub user;
    private final ObjectMapper json;
    private final Duration deadline;
    private final String audience;
    private final int schemaVersion;
    private Map<String, Policy> policies;

    public PropertyAuthorizationContext(StringRedisTemplate redis, GrpcChannelFactory channels,
        ObjectMapper json,
        @Value("${vistay.authorization.grpc-deadline:2s}") Duration deadline,
        @Value("${vistay.security.audience:vistay-backoffice-api}") String audience,
        @Value("${vistay.authorization.role-policy-schema-version:2}") int schemaVersion) {
        this.redis = redis;
        this.user = UserAuthorizationServiceGrpc.newBlockingStub(channels.createChannel("user"));
        this.json = json;
        this.deadline = deadline;
        this.audience = audience;
        this.schemaVersion = schemaVersion;
    }

    public Set<String> require(Jwt jwt, String permission) {
        initialize(jwt);
        var scopes = new LinkedHashSet<String>();
        policies.values().forEach(policy -> policy.permissions().stream()
            .filter(item -> permission.equals(item.permissionCode()))
            .forEach(item -> scopes.addAll(item.allowedScopes())));
        if (scopes.isEmpty()) throw error("FORBIDDEN_PERMISSION", "Missing permission " + permission);
        return Set.copyOf(scopes);
    }

    private void initialize(Jwt jwt) {
        if (policies != null) return;
        if (jwt == null) throw error("UNAUTHENTICATED", "Authentication is required");
        var accountType = accountType(jwt);
        var roles = roles(jwt);
        if (roles.isEmpty()) { policies = Map.of(); return; }
        var roleCodes = List.copyOf(roles);
        var keys = roleCodes.stream().map(role ->
            "vistay:authz:role-policy:v2:" + accountType + ":" + role).toList();
        List<String> values;
        try { values = redis.opsForValue().multiGet(keys); }
        catch (RuntimeException unavailable) { policies = load(accountType, roleCodes); return; }
        var found = new LinkedHashMap<String, Policy>();
        var misses = new ArrayList<String>();
        for (var index = 0; index < roleCodes.size(); index++) {
            var raw = values != null && index < values.size() ? values.get(index) : null;
            var policy = decode(raw, accountType, roleCodes.get(index));
            if (policy == null) misses.add(roleCodes.get(index)); else found.put(policy.roleCode(), policy);
        }
        if (!misses.isEmpty()) found.putAll(load(accountType, misses));
        policies = Map.copyOf(found);
    }

    private Map<String, Policy> load(String accountType, List<String> roles) {
        try {
            var response = user.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                .getRolePolicies(GetRolePoliciesRequest.newBuilder()
                    .setAccountType(AccountType.valueOf("ACCOUNT_TYPE_" + accountType))
                    .addAllRoleCodes(roles).build());
            if (response.getSchemaVersion() != schemaVersion) throw new IllegalStateException();
            var result = new LinkedHashMap<String, Policy>();
            response.getPoliciesList().forEach(policy -> result.put(policy.getRoleCode(), new Policy(
                policy.getRoleCode(), policy.getPermissionsList().stream().map(permission -> new Permission(
                    permission.getPermissionCode(), permission.getAllowedScopesList().stream()
                        .filter(scope -> scope != DataScopeType.DATA_SCOPE_TYPE_UNSPECIFIED)
                        .map(scope -> scope.name().substring("DATA_SCOPE_TYPE_".length())).toList()
                )).toList())));
            return result;
        } catch (StatusRuntimeException | IllegalStateException unavailable) {
            throw error("AUTHORIZATION_UNAVAILABLE", "Authorization policy is unavailable");
        }
    }

    private Policy decode(String raw, String accountType, String roleCode) {
        if (raw == null) return null;
        try {
            var value = json.readValue(raw, CacheEntry.class);
            return value.active() && value.schemaVersion() == schemaVersion
                && accountType.equals(value.accountType()) && roleCode.equals(value.roleCode())
                ? value.policy() : null;
        } catch (RuntimeException invalid) { return null; }
    }

    private Set<String> roles(Jwt jwt) {
        var access = jwt.getClaimAsMap("resource_access");
        if (access == null || !(access.get(audience) instanceof Map<?, ?> client)
            || !(client.get("roles") instanceof List<?> values)) return Set.of();
        var roles = new LinkedHashSet<String>();
        values.forEach(value -> { if (value instanceof String role
            && role.matches("^[A-Z][A-Z0-9_]{1,63}$")) roles.add(role); });
        return roles;
    }

    private static String accountType(Jwt jwt) {
        var value = jwt.getClaimAsString("account_type");
        var normalized = value == null ? "ADMIN" : value.trim().toUpperCase();
        if (!Set.of("ADMIN", "CUSTOMER", "HOST").contains(normalized)) {
            throw error("INVALID_TOKEN", "Token account type is invalid");
        }
        return normalized;
    }

    private static GraphqlErrorException error(String code, String message) {
        return GraphqlErrorException.newErrorException().message(message)
            .errorClassification("AUTHORIZATION_UNAVAILABLE".equals(code)
                ? ErrorType.INTERNAL_ERROR : ErrorType.FORBIDDEN)
            .extensions(Map.of("code", code)).build();
    }

    private record CacheEntry(String accountType, String roleCode, boolean active,
        long catalogVersion, int schemaVersion, Policy policy) {}
    private record Policy(String roleCode, List<Permission> permissions) {}
    private record Permission(String permissionCode, List<String> allowedScopes) {}
}
