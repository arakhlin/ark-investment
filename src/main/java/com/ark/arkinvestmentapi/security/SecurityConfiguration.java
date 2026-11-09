package com.ark.arkinvestmentapi.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import java.io.IOException;

@Configuration
public class SecurityConfiguration {
    @Bean
    JwtDecoder jwtDecoder(@Value("${ark.security.issuer}") String issuer,
                          @Value("${ark.security.audience}") String audience,
                          @Value("${ark.security.jwk-set-uri}") String jwks) {
        if (issuer.isBlank() || audience.isBlank() || jwks.isBlank())
            throw new IllegalArgumentException("JWT issuer, audience and JWKS must be configured");
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), jwt -> {
            var audiences = jwt.getAudience();
            boolean valid = audiences != null && audiences.contains(audience) && jwt.getExpiresAt() != null
                    && jwt.getSubject() != null && !jwt.getSubject().isBlank();
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Required token claims missing or invalid", null));
        }));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> {}).authenticationEntryPoint(
                        (req, res, ex) -> problem(res, 401)))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> problem(res, 401))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403))).build();
    }

    private static void problem(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String title = status == 401 ? "Unauthorized" : "Forbidden";
        String detail = status == 401 ? "A valid bearer access token is required" : "Insufficient permission";
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title
                + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
