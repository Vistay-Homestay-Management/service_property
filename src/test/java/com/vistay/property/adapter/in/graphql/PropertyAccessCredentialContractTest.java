package com.vistay.property.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PropertyAccessCredentialContractTest {
    @Test
    void accessCodeIsVaultProtectedAndNeverReturnedByGraphQl() throws Exception {
        var controller = Files.readString(Path.of(
            "src/main/java/com/vistay/property/adapter/in/graphql/PropertyPortfolioController.java"));
        var protection = Files.readString(Path.of(
            "src/main/java/com/vistay/property/adapter/out/security/VaultPropertyAccessProtection.java"));
        var schema = Files.readString(Path.of("src/main/resources/graphql/schema.graphqls"));

        assertThat(controller).contains(
            "@RequiresPermission(\"property:update\")",
            "accessProtection.protect(input.propertyId(), input.accessCode())",
            "access_password_secret_ref = :secretRef",
            "STALE_PROPERTY_VERSION"
        );
        assertThat(protection).contains(
            "/encrypt/",
            "X-Vault-Token",
            "Base64.getEncoder()",
            "vault"
        );
        assertThat(schema).contains(
            "propertyAccessCredential(propertyId: ID!): PropertyAccessCredential!",
            "setPropertyAccessCredential(input: PropertyAccessCredentialInput!): PropertyAccessCredential!"
        );
        assertThat(schema).doesNotContain("accessPasswordSecretRef", "ciphertext: String");
    }
}
