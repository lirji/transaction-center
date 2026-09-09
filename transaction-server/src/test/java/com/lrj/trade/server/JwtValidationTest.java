package com.lrj.trade.server;

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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.security.oauth2.jwt.JwtException;

/** 使用真实RSA签名和本地JWKS验证安全配置，不把模拟解码器当签名验收。 */
class JwtValidationTest {
    @Test void signatureIssuerAudienceAndExpiryAreAllRequired() throws Exception {
        RSAKey key=new RSAKeyGenerator(2048).keyID("test-key").generate();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/keys",exchange->{
            byte[] bytes=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var decoder=new SecurityConfiguration().jwtDecoder("https://issuer.test","http://127.0.0.1:"+server.getAddress().getPort()+"/keys","transaction-center");
            Instant future=Instant.now().plusSeconds(600);
            assertEquals("member",decoder.decode(token(key,"https://issuer.test","transaction-center",future)).getSubject());
            assertThrows(JwtException.class,()->decoder.decode(token(key,"https://other.test","transaction-center",future)));
            assertThrows(JwtException.class,()->decoder.decode(token(key,"https://issuer.test","other-app",future)));
            assertThrows(JwtException.class,()->decoder.decode(token(key,"https://issuer.test","transaction-center",Instant.now().minusSeconds(600))));
            assertThrows(JwtException.class,()->decoder.decode(token(key,"https://issuer.test","transaction-center",null)));
            for(Object tenant : new Object[]{null, 900001, "0", "-1", "+1", "01", " 1", "1 ", "1.0", "9223372036854775808", ""}) {
                assertThrows(JwtException.class,()->decoder.decode(token(key,"https://issuer.test","transaction-center",future,tenant,"member")));
            }
            for(String subject : new String[]{null,""," "}) {
                assertThrows(JwtException.class,()->decoder.decode(token(key,"https://issuer.test","transaction-center",future,"900001",subject)));
            }
            assertEquals("900001", decoder.decode(token(key,"https://issuer.test","transaction-center",future,"900001","member")).getClaim("tenant_id"));
            RSAKey attacker=new RSAKeyGenerator(2048).keyID("test-key").generate();
            assertThrows(JwtException.class,()->decoder.decode(token(attacker,"https://issuer.test","transaction-center",future)));
        } finally { server.stop(0); }
    }
    private String token(RSAKey key,String issuer,String audience,Instant expiry) throws Exception {
        return token(key,issuer,audience,expiry,"123","member");
    }
    private String token(RSAKey key,String issuer,String audience,Instant expiry,Object tenant,String subject) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject)
            .claim("tenant_id",tenant).expirationTime(expiry==null?null:Date.from(expiry)).build();
        SignedJWT jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
