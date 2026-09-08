package com.lrj.trade.server;

import com.lrj.trade.core.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;

/** 本机真实RSA/JWKS，不用解码器替身冒充密码学和网络边界验证。 */
class OrderAttributionVerifierTest {
    @Test void rsaClaimsAndExactTimeBoundaries() throws Exception {
        try(var f=new Fixture()) {
            var verified=f.verifier.verify(f.actor,2,3,f.token(Map.of()));
            assertEquals("ref-one",verified.referralRef());
            assertEquals("VerifiedOrderAttribution[REDACTED]",verified.toString());
            assertDoesNotThrow(()->verified.requireUsable(f.actor,2,3,f.now.plusSeconds(899)));
            assertThrows(TradeException.class,()->verified.requireUsable(f.actor,2,3,f.now.plusSeconds(900)));
            for(var mutation:List.<Map<String,Object>>of(Map.of("iss","https://evil.test"),Map.of("aud","transaction-center"),
                Map.of("aud",List.of(OrderAttributionVerifier.AUDIENCE,"transaction-center")),Map.of("tenant_id","8"),
                Map.of("actor_key",new Actor(7,"https://identity.test","other").key()),Map.of("member_id","4"),Map.of("quote_id","4"),
                Map.of("jti",123),Map.of("member_id",2),Map.of("quote_id","03"),Map.of("definition_version",0),Map.of("definition_version",1.5),
                Map.of("definition_version","1"),Map.of("schema_version",2),Map.of("token_kind","OTHER"),
                Map.of("jti","a".repeat(65)),Map.of("campaign_id",""),Map.of("referral_ref","a".repeat(65)),
                Map.of("iat",Date.from(f.now.plusSeconds(1))),Map.of("nbf",Date.from(f.now.plusSeconds(1))),
                Map.of("exp",Date.from(f.now)),Map.of("exp",Date.from(f.now.plusSeconds(901)))))
                rejected(f,f.token(mutation));
            for(String field:List.of("iss","aud","iat","exp","jti","tenant_id","actor_key","member_id","quote_id",
                "campaign_id","definition_version","referral_ref","schema_version","token_kind")) {
                var mutation=new HashMap<String,Object>();mutation.put(field,null);rejected(f,f.token(mutation));
            }
            var absentIssued=new HashMap<String,Object>(); absentIssued.put("iat",null); absentIssued.put("exp",Date.from(f.now.plusSeconds(1)));
            rejected(f,f.token(absentIssued));
            assertEquals(1,f.requests.get());
        }
    }
    @Test void signatureAndAlgorithmCannotBeSubstituted() throws Exception {
        try(var f=new Fixture()) {
            var other=new RSAKeyGenerator(2048).keyID("key").generate();
            rejected(f,f.token(other,JWSAlgorithm.RS256,Map.of()));
            rejected(f,f.token(f.key,JWSAlgorithm.RS512,Map.of()));
            rejected(f,"not.a.jwt"); rejected(f,"a".repeat(16385));
            rejected(f,new PlainJWT(new JWTClaimsSet.Builder().build()).serialize());
            assertEquals(1,f.requests.get());
        }
    }
    @Test void outerTransactionFailsBeforeAnyJwksRequest() throws Exception {
        try(var f=new Fixture()) {
            var token=f.token(Map.of());
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertEquals("ORDER_ATTRIBUTION_TRANSACTION",assertThrows(TradeException.class,()->f.verifier.verify(f.actor,2,3,token)).code());
                assertEquals(0,f.requests.get());
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        }
    }
    @Test void slowJwksReadIsBoundedAndSanitized() throws Exception {
        try(var f=new Fixture()) {
            f.delay=true;
            assertTimeoutPreemptively(Duration.ofSeconds(4),()->rejected(f,f.token(Map.of())));
        }
    }
    private static void rejected(Fixture f,String token) {
        var error=assertThrows(TradeException.class,()->f.verifier.verify(f.actor,2,3,token));
        assertEquals("ORDER_ATTRIBUTION_UNTRUSTED",error.code());assertNull(error.getCause());
        assertEquals("订单归因令牌无效",error.getMessage());
    }
    static final class Fixture implements AutoCloseable {
        final Instant now=Instant.parse("2000-01-01T00:00:00Z");
        final Actor actor=new Actor(7,"https://identity.test","buyer");
        final RSAKey key=new RSAKeyGenerator(2048).keyID("key").generate();
        final AtomicInteger requests=new AtomicInteger();
        volatile boolean delay;
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final OrderAttributionVerifier verifier;
        Fixture() throws Exception {
            server.createContext("/keys",exchange->{
                requests.incrementAndGet();
                if(delay) try { Thread.sleep(2500); } catch(InterruptedException e) {Thread.currentThread().interrupt();}
                byte[] body=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
                try(var output=exchange.getResponseBody()) {output.write(body);}
            });server.start();
            verifier=OrderAttributionVerifier.create("https://marketing.test","http://127.0.0.1:"+server.getAddress().getPort()+"/keys",Clock.fixed(now,ZoneOffset.UTC));
        }
        String token(Map<String,Object> changes) throws Exception { return token(key,JWSAlgorithm.RS256,changes); }
        String token(RSAKey signing,JWSAlgorithm algorithm,Map<String,Object> changes) throws Exception {
            Map<String,Object> claims=new HashMap<>();
            claims.put("iss","https://marketing.test");claims.put("aud",OrderAttributionVerifier.AUDIENCE);
            claims.put("iat",Date.from(now));claims.put("exp",Date.from(now.plusSeconds(900)));claims.put("jti","one");
            claims.put("tenant_id","7");claims.put("actor_key",actor.key());claims.put("member_id","2");claims.put("quote_id","3");
            claims.put("referral_ref","ref-one");claims.put("campaign_id","campaign");claims.put("definition_version",1);
            claims.put("token_kind","ORDER_REFERRAL_ATTRIBUTION_V1");claims.put("schema_version",1);claims.putAll(changes);
            var builder=new JWTClaimsSet.Builder();claims.forEach(builder::claim);
            var jwt=new SignedJWT(new JWSHeader.Builder(algorithm).keyID(signing.getKeyID()).build(),builder.build());
            jwt.sign(new RSASSASigner(signing));return jwt.serialize();
        }
        @Override public void close() {server.stop(0);}
    }
}
