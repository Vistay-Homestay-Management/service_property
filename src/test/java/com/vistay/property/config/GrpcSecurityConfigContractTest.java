package com.vistay.property.config;

import org.junit.jupiter.api.Test;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcSecurityConfigContractTest {
    @Test
    void exposesCertificatePrincipalAsAuthenticatedInternalService() throws Exception {
        var config = new GrpcSecurityConfig();
        UserDetailsService users = config.grpcClientDetails();
        var principal = users.loadUserByUsername("CN=service-booking");

        assertThat(principal.getUsername()).isEqualTo("CN=service-booking");
        assertThat(principal.getAuthorities()).extracting(Object::toString)
            .containsExactly("ROLE_INTERNAL_SERVICE");
        assertThat(GrpcSecurityConfig.class.getDeclaredMethod(
            "grpcSecurityFilterChain", org.springframework.grpc.server.security.GrpcSecurity.class)
            .isAnnotationPresent(org.springframework.context.annotation.Bean.class)).isTrue();
        assertThat(GrpcSecurityConfig.class.getDeclaredMethod(
            "grpcSecurityFilterChain", org.springframework.grpc.server.security.GrpcSecurity.class)
            .isAnnotationPresent(GlobalServerInterceptor.class)).isTrue();
        assertThat(AuthenticationProcessInterceptor.class).isNotNull();
    }
}
