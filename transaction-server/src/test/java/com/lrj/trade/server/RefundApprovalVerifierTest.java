package com.lrj.trade.server;

import com.lrj.trade.core.Actor;
import com.lrj.trade.core.TradeException;
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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 真实RSA与本地JWKS验证审批回执，不能以Mock解码器冒充防伪造验收。 */
class RefundApprovalVerifierTest {
    @Test void exactTimeAndRequiredClaimBoundariesAreEnforced() throws Exception {
        try(var f=new Fixture(Instant.parse("2000-01-01T00:00:00Z"))) {
            assertTrue(f.verifier.verify(f.caller,f.token(f.key,Map.of("exp",Date.from(f.now.plusSeconds(300))))).approve());
            assertTrue(f.verifier.verify(f.caller,f.token(f.key,Map.of("iat",Date.from(f.now.plusSeconds(30))))).approve());
            for(var mutation:List.<Map<String,Object>>of(Map.of("exp",Date.from(f.now)),
                Map.of("exp",Date.from(f.now.plusSeconds(301))),Map.of("iat",Date.from(f.now.plusSeconds(31))),
                Map.of("tenant_id",7),Map.of("schema_version","1"),Map.of("decision_version",9007199254740992L)))
                assertRejected(f,f.token(f.key,mutation));
            for(String field:List.of("iss","aud","sub","tenant_id","schema_version","decision_version","approver_issuer",
                "approver_subject","evidence_ref","jti","refund_no","approval_ref")) {
                var missing=new HashMap<String,Object>(); missing.put(field,null);
                assertRejected(f,f.token(f.key,missing));
            }
            assertRejected(f,new PlainJWT(new JWTClaimsSet.Builder().subject("workflow-service").build()).serialize());
            var unknownKey=new RSAKeyGenerator(2048).keyID("unknown-key").generate();
            assertRejected(f,f.token(unknownKey,Map.of()));
        }
    }
    @Test void injectedClockControlsBothJwtAndBusinessTimeValidation() throws Exception {
        try(var f=new Fixture(Instant.parse("2000-01-01T00:00:00Z"))) {
            assertTrue(f.verifier.verify(f.caller,f.token(f.key,Map.of())).approve());
            assertRejected(f,f.token(f.key,Map.of("nbf",Date.from(f.now.plusSeconds(120)))));
        }
    }
    @Test void signatureRoutingAndHumanDecisionAreBoundTogether() throws Exception {
        try(var f=new Fixture()) {
            var receipt=f.verifier.verify(f.caller,f.token(f.key,Map.of()));
            assertEquals("RF-one",receipt.refundNo()); assertEquals("RA-one",receipt.approvalRef());
            assertEquals("reviewer",receipt.approver().subject()); assertEquals(7,receipt.approver().tenantId());
            assertTrue(receipt.approve()); assertEquals(1,receipt.decisionVersion());
            assertEquals("https://workflow.test",receipt.sourceIssuer()); assertEquals("workflow-service",receipt.sourceSubject());
            assertEquals("VerifiedApprovalReceipt[REDACTED]",receipt.toString());
            assertFalse(f.verifier.verify(f.caller,f.token(f.key,Map.of("decision","REJECT"))).approve());
            for(var mutation:List.<Map<String,Object>>of(
                Map.of("iss","https://attacker.test"),Map.of("aud","transaction-center"),Map.of("sub","other-service"),
                Map.of("tenant_id","8"),Map.of("approver_issuer","https://attacker.test"),Map.of("approver_subject","workflow-service"),
                Map.of("decision","SUCCESS"),Map.of("decision_version",1.5),Map.of("decision_version",0),
                Map.of("decision_version","1"),Map.of("schema_version",2),Map.of("receipt_kind","PAYMENT"),
                Map.of("jti",""),Map.of("refund_no",""),Map.of("approval_ref",""))) {
                assertRejected(f,f.token(f.key,mutation));
            }
            var attacker=new RSAKeyGenerator(2048).keyID("approval-key").generate();
            assertRejected(f,f.token(attacker,Map.of()));
            assertRejected(f,"not.a.signature"); assertRejected(f,"x".repeat(16385));
            assertThrows(TradeException.class,()->f.verifier.verify(new Actor(7,"https://identity.test","admin"),receipt.signedReceipt()));
            assertThrows(TradeException.class,()->f.verifier.verify(new Actor(7,"https://other.test","workflow-service"),receipt.signedReceipt()));
        }
    }
    @Test void missingOrExcessiveValidityWindowIsRejected() throws Exception {
        try(var f=new Fixture()) {
            assertRejected(f,f.token(f.key,Map.of("exp",Date.from(f.now.minusSeconds(1)))));
            assertRejected(f,f.token(f.key,Map.of("exp",Date.from(f.now.plusSeconds(601)))));
            assertRejected(f,f.token(f.key,Map.of("iat",Date.from(f.now.plusSeconds(60)))));
            var missing=new HashMap<String,Object>(); missing.put("exp",null);
            assertRejected(f,f.token(f.key,missing));
            missing.clear(); missing.put("iat",null); assertRejected(f,f.token(f.key,missing));
        }
    }
    private void assertRejected(Fixture f,String token) {
        var error=assertThrows(TradeException.class,()->f.verifier.verify(f.caller,token));
        assertEquals("REFUND_APPROVAL_UNTRUSTED",error.code());
        assertEquals("审批回执认证失败",error.getMessage()); assertNull(error.getCause());
    }
    private static final class Fixture implements AutoCloseable {
        final Instant now;
        final RSAKey key=new RSAKeyGenerator(2048).keyID("approval-key").generate();
        final Actor caller=new Actor(7,"https://identity.test","workflow-service");
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final RefundApprovalVerifier verifier;
        Fixture() throws Exception { this(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)); }
        Fixture(Instant now) throws Exception {
            this.now=now;
            server.createContext("/keys",exchange->{
                byte[] body=new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length);
                try(var output=exchange.getResponseBody()) { output.write(body); }
            }); server.start();
            verifier=RefundApprovalVerifier.create("https://workflow.test","http://127.0.0.1:"+server.getAddress().getPort()+"/keys",
                "transaction-refund-approval","https://identity.test","workflow-service","https://identity.test",Clock.fixed(now,ZoneOffset.UTC));
        }
        String token(RSAKey signing,Map<String,Object> changes) throws Exception {
            Map<String,Object> claims=new HashMap<>();
            claims.put("iss","https://workflow.test"); claims.put("aud","transaction-refund-approval"); claims.put("sub","workflow-service");
            claims.put("iat",Date.from(now)); claims.put("exp",Date.from(now.plusSeconds(120))); claims.put("jti","decision-one");
            claims.put("tenant_id","7"); claims.put("receipt_kind","REFUND_APPROVAL_DECISION_V1"); claims.put("schema_version",1L);
            claims.put("approver_issuer","https://identity.test"); claims.put("approver_subject","reviewer");
            claims.put("refund_no","RF-one"); claims.put("approval_ref","RA-one"); claims.put("decision","APPROVE");
            claims.put("decision_version",1L); claims.put("evidence_ref","workflow:case-one:decision-one"); claims.putAll(changes);
            var builder=new JWTClaimsSet.Builder(); claims.forEach(builder::claim);
            var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signing.getKeyID()).build(),builder.build());
            jwt.sign(new RSASSASigner(signing)); return jwt.serialize();
        }
        @Override public void close() { server.stop(0); }
    }
}
