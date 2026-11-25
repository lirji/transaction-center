package com.lrj.trade.server.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.lrj.trade.core.Checks;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

/** 以契约HTTP对端和真实MySQL核对权限、首份证据及并发重放。 */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "trade.security.issuer=https://checkout.test", "trade.security.jwk-set-uri=https://checkout.test/keys",
    "trade.security.audience=transaction-center", "trade.checkout-pilot.enabled=true",
    "trade.checkout-pilot.tenant-id=906021", "trade.checkout-pilot.scope-version=lc02-store-r1",
    "trade.checkout-pilot.commerce-tenant=lc02-pilot", "trade.checkout-pilot.merchant-id=lc02-merchant",
    "trade.checkout-pilot.store-id=lc02-store-a", "trade.checkout-pilot.sku-id=lc02-mug-a"
})
class CheckoutPilotQuoteTest {
    private static final String ISSUER = "https://checkout.test";
    private static final String SUBJECT = "lc02-checkout-machine";
    private static final String TENANT = "906021";
    private static final String OWNER = "b".repeat(64);
    private static final String OUTGOING_TOKEN = UUID.randomUUID() + "-" + UUID.randomUUID();
    private static final String ELIGIBLE_QUOTE = UUID.randomUUID().toString();
    private static final String UNKNOWN_QUOTE = UUID.randomUUID().toString();
    private static final String UNAVAILABLE_QUOTE = UUID.randomUUID().toString();
    private static final AtomicInteger CALLED = new AtomicInteger();
    private static final HttpServer COMMERCE = commerce();

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11").withDatabaseName("transaction_center_dev");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
            + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.user", MYSQL::getUsername);
        registry.add("spring.flyway.password", MYSQL::getPassword);
        registry.add("trade.checkout-pilot.commerce-base-url", () -> "http://127.0.0.1:" + COMMERCE.getAddress().getPort());
        registry.add("trade.checkout-pilot.commerce-token", () -> OUTGOING_TOKEN);
        registry.add("trade.checkout-pilot.caller-key", () -> Checks.hash(ISSUER, SUBJECT));
    }

    @AfterAll
    static void stopCommerce() {
        COMMERCE.stop(0);
    }

    @LocalServerPort int port;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void preparesOneReferenceAndValidatesTheCallerAndCommerceProof() throws Exception {
        when(decoder.decode("allowed")).thenReturn(jwt("allowed", SUBJECT, TENANT, "trade.checkout-pilot.prepare"));
        when(decoder.decode("other")).thenReturn(jwt("other", "other-machine", TENANT, "trade.checkout-pilot.prepare"));
        when(decoder.decode("wrong-tenant")).thenReturn(jwt("wrong-tenant", SUBJECT, "906022", "trade.checkout-pilot.prepare"));
        when(decoder.decode("no-scope")).thenReturn(jwt("no-scope", SUBJECT, TENANT, "trade.admin"));

        assertEquals(403, send("other", ELIGIBLE_QUOTE, OWNER).statusCode());
        assertEquals(403, send("wrong-tenant", ELIGIBLE_QUOTE, OWNER).statusCode());
        assertEquals(403, send("no-scope", ELIGIBLE_QUOTE, OWNER).statusCode());
        assertEquals(0, CALLED.get());
        assertEquals(400, send("allowed", "invalid", OWNER).statusCode());
        assertEquals(409, send("allowed", UNKNOWN_QUOTE, OWNER).statusCode());
        assertEquals(503, send("allowed", UNAVAILABLE_QUOTE, OWNER).statusCode());
        assertEquals(409, send("allowed", ELIGIBLE_QUOTE, "c".repeat(64)).statusCode());
        assertEquals(0, count());

        var first = http.sendAsync(request("allowed", ELIGIBLE_QUOTE, OWNER), HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(request("allowed", ELIGIBLE_QUOTE, OWNER), HttpResponse.BodyHandlers.ofString());
        var expected = first.get(15, TimeUnit.SECONDS);
        var replay = second.get(15, TimeUnit.SECONDS);
        assertEquals(200, expected.statusCode(), expected.body());
        assertEquals(200, replay.statusCode(), replay.body());
        var prepared = json.readTree(expected.body());
        assertEquals(prepared, json.readTree(replay.body()));
        assertTrue(prepared.path("tradeQuoteRef").asString().matches(CheckoutPilotQuoteService.QUOTE_ID_PATTERN));
        assertEquals(ELIGIBLE_QUOTE, prepared.path("commerceQuoteId").asString());
        assertEquals("lc02-store-a", prepared.path("storeId").asString());
        assertFalse(prepared.has("orderId"));
        assertEquals(1, count());
        int before = CALLED.get();
        assertEquals(prepared, json.readTree(send("allowed", ELIGIBLE_QUOTE, OWNER).body()));
        assertEquals(409, send("allowed", ELIGIBLE_QUOTE, "c".repeat(64)).statusCode());
        assertEquals(before, CALLED.get());
        String stored = jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(evidence_json, '$.items[0].unitPriceMinor'))"
            + " FROM tc_checkout_pilot_quote_ref WHERE tenant_id=?", String.class, Long.parseLong(TENANT));
        assertEquals("5900", stored);
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tc_checkout_pilot_quote_ref", Integer.class);
    }

    private HttpResponse<String> send(String token, String quote, String owner) throws Exception {
        return http.send(request(token, quote, owner), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String token, String quote, String owner) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + CheckoutPilotQuoteController.PATH))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("commerceQuoteId", quote, "ownerRef", owner))))
            .build();
    }

    private static Jwt jwt(String token, String subject, String tenant, String scope) {
        return Jwt.withTokenValue(token).header("alg", "RS256").issuer(ISSUER).subject(subject)
            .claim("tenant_id", tenant).claim("scope", scope).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
            .build();
    }

    private static HttpServer commerce() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/checkout-pilot/quotes/", exchange -> {
                CALLED.incrementAndGet();
                try (exchange) {
                    String path = exchange.getRequestURI().getPath();
                    if (!("Bearer " + OUTGOING_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))
                            || !TENANT.equals(exchange.getRequestHeaders().getFirst("X-Trade-Tenant"))) {
                        exchange.sendResponseHeaders(403, -1);
                    } else if (path.endsWith("/" + UNAVAILABLE_QUOTE)) {
                        exchange.sendResponseHeaders(503, -1);
                    } else if (!path.endsWith("/" + ELIGIBLE_QUOTE)) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        byte[] response = """
                            {"scopeVersion":"lc02-store-r1","tradeTenantId":"%s","commerceTenantId":"lc02-pilot",
                             "merchantId":"lc02-merchant","storeId":"lc02-store-a","storeVersion":0,
                             "eligibleSkuId":"lc02-mug-a","commerceQuoteId":"%s","ownerRef":"%s",
                             "currency":"CNY","expiresAt":"%s","items":[{"skuId":"lc02-mug-a","skuRevision":1,
                             "channelPriceVersion":0,"quantity":2,"unitPriceMinor":"5900"}]}
                            """.formatted(TENANT, ELIGIBLE_QUOTE, OWNER, Instant.now().plusSeconds(120)).getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, response.length);
                        exchange.getResponseBody().write(response);
                    }
                }
            });
            server.start();
            return server;
        } catch (IOException error) {
            throw new IllegalStateException("无法启动隔离商城契约对端", error);
        }
    }
}
