package com.ark.arkinvestmentapi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.List;
import java.util.UUID;

abstract class PostgresTestSupport {
    static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    @Autowired JdbcTemplate clientJdbc;

    @BeforeEach
    void seedIdentity() {
        clientJdbc.update("insert into clients(id,name,status) values (?,'Test client','ACTIVE') on conflict(id) do update set status='ACTIVE'", CLIENT);
        clientJdbc.update("insert into app_users(id,issuer,subject,status,user_type,client_id,role) values (?,'https://issuer.test','test-admin','ACTIVE','CLIENT',?,'CLIENT_ADMIN') on conflict(id) do update set status='ACTIVE',role='CLIENT_ADMIN'", ADMIN, CLIENT);
        clientJdbc.update("insert into trusted_identities(issuer,subject,user_type,client_id) values ('https://issuer.test','test-admin','CLIENT',?) on conflict do nothing",CLIENT);
        authenticate();
    }

    static void authenticate() {
        var jwt = Jwt.withTokenValue("test")
                .header("alg","RS256").issuer("https://issuer.test").subject("test-admin").build();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearIdentity() { SecurityContextHolder.clearContext(); }

    // One isolated database for this test JVM, cleaned up by Testcontainers/Ryuk.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector", () -> TestSqlCapture.class.getName());
        registry.add("ark.security.issuer", () -> "https://issuer.test");
        registry.add("ark.security.audience", () -> "ark-api");
        registry.add("ark.security.jwk-set-uri", () -> TestTokens.JWKS);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
