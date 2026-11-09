package com.ark.arkinvestmentapi;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

final class TestTokens {
    static final RSAKey KEY;
    static final String JWKS;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test").generate();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/jwks", exchange -> {
                var bytes = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);
                try (var out=exchange.getResponseBody()) { out.write(bytes); }
            });
            var starter = new Thread(server::start); starter.setDaemon(true); starter.start(); starter.join();
            JWKS = "http://127.0.0.1:"+server.getAddress().getPort()+"/jwks";
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static String token(String subject, String issuer, String audience, Instant expiration, boolean wrongKey) throws Exception {
        var key=wrongKey ? new RSAKeyGenerator(2048).keyID("test").generate() : KEY;
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),
                new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience)
                        .issueTime(Date.from(Instant.now().minusSeconds(10)))
                        .expirationTime(expiration == null ? null : Date.from(expiration)).build());
        jwt.sign(new RSASSASigner(key)); return jwt.serialize();
    }
}
