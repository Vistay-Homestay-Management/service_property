package com.vistay.property.adapter.out.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class VaultPropertyAccessProtection {
    private static final String VAULT_TOKEN_HEADER = "X-Vault-Token";

    private final RestClient vault;
    private final String configuredToken;
    private final String tokenFile;
    private final String transitMount;
    private final String accessKey;

    public VaultPropertyAccessProtection(
        @Value("${vistay.property-access.vault.address:http://localhost:8200}") String address,
        @Value("${vistay.property-access.vault.token:}") String configuredToken,
        @Value("${vistay.property-access.vault.token-file:}") String tokenFile,
        @Value("${vistay.property-access.vault.transit-mount:transit}") String transitMount,
        @Value("${vistay.property-access.vault.access-key:vistay-property-access}") String accessKey
    ) {
        this.vault = RestClient.builder().baseUrl(withoutTrailingSlash(address)).build();
        this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
        this.tokenFile = tokenFile == null ? "" : tokenFile.trim();
        this.transitMount = pathSegment(transitMount, "transit mount");
        this.accessKey = pathSegment(accessKey, "property access key");
    }

    public ProtectedAccessCode protect(UUID propertyId, String accessCode) {
        if (propertyId == null) throw new IllegalArgumentException("propertyId is required");
        var normalized = accessCode == null ? "" : accessCode.trim();
        if (!normalized.matches("^[!-~]{4,32}$")) {
            throw new IllegalArgumentException("Access code must contain 4 to 32 printable characters without spaces");
        }
        var plaintext = "v1:" + propertyId + ":" + normalized;
        Map<?, ?> response;
        try {
            response = vault.post()
                .uri("/v1/" + transitMount + "/encrypt/" + accessKey)
                .header(VAULT_TOKEN_HEADER, token())
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(Map.of("plaintext", Base64.getEncoder().encodeToString(
                    plaintext.getBytes(StandardCharsets.UTF_8))))
                .retrieve()
                .body(Map.class);
        } catch (RestClientException unavailable) {
            throw new PropertyAccessProtectionUnavailable("Vault Transit is unavailable", unavailable);
        }
        if (response == null || !(response.get("data") instanceof Map<?, ?> data)
            || !(data.get("ciphertext") instanceof String ciphertext) || ciphertext.isBlank()) {
            throw new PropertyAccessProtectionUnavailable("Vault response omitted ciphertext");
        }
        var parts = ciphertext.split(":", 3);
        if (parts.length != 3 || !"vault".equals(parts[0]) || !parts[1].startsWith("v")) {
            throw new PropertyAccessProtectionUnavailable("Vault ciphertext has an unsupported format");
        }
        try {
            var keyVersion = Integer.parseInt(parts[1].substring(1));
            if (keyVersion <= 0) throw new NumberFormatException("non-positive version");
            return new ProtectedAccessCode(ciphertext, keyVersion);
        } catch (NumberFormatException invalid) {
            throw new PropertyAccessProtectionUnavailable("Vault ciphertext has an invalid key version", invalid);
        }
    }

    private String token() {
        if (!tokenFile.isBlank()) {
            try {
                var value = Files.readString(Path.of(tokenFile), StandardCharsets.UTF_8).trim();
                if (!value.isBlank()) return value;
            } catch (IOException unreadable) {
                throw new PropertyAccessProtectionUnavailable("Vault token file is unavailable", unreadable);
            }
        }
        if (!configuredToken.isBlank()) return configuredToken;
        throw new PropertyAccessProtectionUnavailable("Vault authentication is not configured");
    }

    private static String pathSegment(String value, String label) {
        var normalized = value == null ? "" : value.trim();
        if (!normalized.matches("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$")) {
            throw new IllegalArgumentException("Invalid Vault " + label);
        }
        return normalized;
    }

    private static String withoutTrailingSlash(String value) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) throw new IllegalArgumentException("Vault address is required");
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    public record ProtectedAccessCode(String ciphertext, int keyVersion) {}

    public static final class PropertyAccessProtectionUnavailable extends RuntimeException {
        public PropertyAccessProtectionUnavailable(String message) { super(message); }
        public PropertyAccessProtectionUnavailable(String message, Throwable cause) { super(message, cause); }
    }
}
