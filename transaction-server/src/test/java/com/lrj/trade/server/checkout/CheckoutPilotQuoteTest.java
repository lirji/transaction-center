package com.lrj.trade.server.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.lrj.trade.catalog.CatalogService;
import com.lrj.trade.core.Actor;
import com.lrj.trade.core.Checks;
import com.lrj.trade.inventory.InventoryService;
import com.lrj.trade.member.MemberService;
import com.lrj.trade.order.MerchantService;
import com.lrj.trade.order.OrderService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
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
    "trade.checkout-pilot.store-id=lc02-store-a", "trade.checkout-pilot.sku-id=lc02-mug-a",
    "trade.checkout-pilot.freeze-enabled=true", "trade.checkout-pilot.marketing-key-id=checkout-test",
    "trade.checkout-pilot.marketing-tenant=lc02-pilot", "trade.checkout-pilot.marketing-organization=lc02-retail",
    "trade.checkout-pilot.marketing-shop=lc02-store-a", "trade.checkout-pilot.marketing-campaign=lc02-threshold",
    "trade.checkout-pilot.marketing-funder=lc02-mug-r1", "trade.checkout-pilot.order-enabled=true",
    "trade.checkout-pilot.native-merchant-no=lc02-trade-merchant", "trade.checkout-pilot.funding-enabled=true",
    "trade.checkout-pilot.funding-key-id=funding-test"
})
class CheckoutPilotQuoteTest {
    private static final String ISSUER = "https://checkout.test";
    private static final String SUBJECT = "lc02-checkout-machine";
    private static final String TENANT = "906021";
    private static final String OUTGOING_TOKEN = UUID.randomUUID() + "-" + UUID.randomUUID();
    private static final String ELIGIBLE_QUOTE = UUID.randomUUID().toString();
    private static final String OWNER = CheckoutPriceProof.sha256(
        "[\"commerce-checkout-owner-v1\",\"lc02-pilot\",\"commerce-buyer\",\"" + ELIGIBLE_QUOTE + "\"]");
    private static final String UNKNOWN_QUOTE = UUID.randomUUID().toString();
    private static final String UNAVAILABLE_QUOTE = UUID.randomUUID().toString();
    private static final Instant SOURCE_EXPIRY = Instant.now().plusSeconds(240).truncatedTo(ChronoUnit.MILLIS);
    private static final KeyPair MARKETING_KEY = signingKey();
    private static final KeyPair FUNDING_KEY = signingKey();
    private static final byte[] ADDRESS_KEY = new java.security.SecureRandom().generateSeed(32);
    private static final AtomicInteger CALLED = new AtomicInteger();
    private static final AtomicInteger STORE_VERSION = new AtomicInteger();
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
        registry.add("trade.checkout-pilot.marketing-public-key", () ->
            Base64.getEncoder().encodeToString(MARKETING_KEY.getPublic().getEncoded()));
        registry.add("trade.checkout-pilot.funding-public-key", () ->
            Base64.getEncoder().encodeToString(FUNDING_KEY.getPublic().getEncoded()));
        registry.add("trade.checkout-pilot.member-links", () -> "{\"1\":\"commerce-buyer\",\"2\":\"another-commerce-buyer\"}");
        registry.add("trade.checkout-pilot.sku-links", () ->
            "{\"lc02-mug-a\":{\"commerceRevision\":1,\"tradeSkuId\":1,\"tradeRevision\":4}}");
        registry.add("trade.address.key-base64", () -> Base64.getEncoder().encodeToString(ADDRESS_KEY));
        registry.add("trade.address.key-version", () -> "checkout-test");
    }

    @AfterAll
    static void stopCommerce() {
        COMMERCE.stop(0);
    }

    @LocalServerPort int port;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MemberService members;
    @Autowired MerchantService merchants;
    @Autowired CatalogService catalog;
    @Autowired InventoryService inventory;
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
        String reference = prepared.path("tradeQuoteRef").asString();
        var proofHash = assertFrozenPrice(reference, prepared.path("sourceDigest").asString());
        assertNativeOrder(reference, proofHash);
    }

    private String assertFrozenPrice(String reference, String sourceDigest) throws Exception {
        when(decoder.decode("freeze")).thenReturn(jwt("freeze", SUBJECT, TENANT, "trade.checkout-pilot.freeze"));
        when(decoder.decode("freeze-other")).thenReturn(jwt("freeze-other", "other-machine", TENANT, "trade.checkout-pilot.freeze"));
        when(decoder.decode("freeze-wrong-tenant")).thenReturn(jwt("freeze-wrong-tenant", SUBJECT, "906022", "trade.checkout-pilot.freeze"));
        var claims = claims(reference, sourceDigest);
        String proof = sign(claims);
        assertEquals(403, freeze("allowed", reference, proof).statusCode());
        assertEquals(403, freeze("freeze-other", reference, proof).statusCode());
        assertEquals(403, freeze("freeze-wrong-tenant", reference, proof).statusCode());
        assertEquals(400, freeze("freeze", "invalid", proof).statusCode());
        assertEquals(404, freeze("freeze", UUID.randomUUID().toString(), proof).statusCode());
        assertEquals(409, freeze("freeze", reference, "x" + proof).statusCode());
        assertEquals(409, freeze("freeze", reference, proof + "==").statusCode());
        assertEquals(409, freeze("freeze", reference, modified(claims, "sourceDigest", "f".repeat(64))).statusCode());
        assertEquals(409, freeze("freeze", reference, modified(claims, "payableMinor", "100")).statusCode());
        var free = new TreeMap<>(claims);
        free.put("payableMinor", "0");
        free.put("discountMinor", "11800");
        free.put("lines", "lc02-mug-a~lc02-mug-a~2~5900~0");
        assertEquals(409, freeze("freeze", reference, sign(free)).statusCode());
        assertEquals(409, freeze("freeze", reference, modified(claims, "eligibleSkuId", "lc02-mug-b")).statusCode());
        assertEquals(409, freeze("freeze", reference, modified(claims, "expiresAt", SOURCE_EXPIRY.plusSeconds(1).toString())).statusCode());
        assertEquals(409, freeze("freeze", reference, modified(claims, "expiresAt", Instant.now().minusSeconds(1).toString())).statusCode());
        STORE_VERSION.incrementAndGet();
        assertEquals(409, freeze("freeze", reference, proof).statusCode());
        STORE_VERSION.decrementAndGet();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tc_checkout_pilot_quote_ref WHERE price_proof_hash IS NOT NULL", Integer.class));

        var first = http.sendAsync(freezeRequest("freeze", reference, proof), HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(freezeRequest("freeze", reference, proof), HttpResponse.BodyHandlers.ofString());
        var accepted = first.get(15, TimeUnit.SECONDS);
        var same = second.get(15, TimeUnit.SECONDS);
        assertEquals(200, accepted.statusCode(), accepted.body());
        assertEquals(200, same.statusCode(), same.body());
        var receipt = json.readTree(accepted.body());
        assertEquals(receipt, json.readTree(same.body()));
        assertEquals("11800", receipt.path("grossMinor").asString());
        assertEquals("1000", receipt.path("discountMinor").asString());
        assertEquals("10800", receipt.path("payableMinor").asString());
        assertEquals(reference, receipt.path("tradeQuoteRef").asString());
        assertFalse(receipt.has("checkoutQuoteToken"));
        assertEquals(CheckoutPriceProof.sha256(proof), jdbc.queryForObject(
            "SELECT price_proof_hash FROM tc_checkout_pilot_quote_ref WHERE tenant_id=? AND quote_ref=?",
            String.class, Long.parseLong(TENANT), reference));
        int before = CALLED.get();
        assertEquals(receipt, json.readTree(freeze("freeze", reference, proof).body()));
        assertEquals(409, freeze("freeze", reference, modified(claims, "marketingQuoteId", UUID.randomUUID().toString())).statusCode());
        assertEquals(before, CALLED.get());
        return CheckoutPriceProof.sha256(proof);
    }

    private void assertNativeOrder(String reference, String proofHash) throws Exception {
        long tenant = Long.parseLong(TENANT);
        var buyer = new Actor(tenant, ISSUER, "buyer");
        var other = new Actor(tenant, ISSUER, "other-buyer");
        members.register(buyer, "参与会员");
        members.register(other, "对照会员");
        assertEquals("1", members.me(buyer).memberId());
        assertEquals("2", members.me(other).memberId());
        merchants.create(buyer, "merchant", "lc02-trade-merchant", "隔离试点商户");
        long product = Long.parseLong(catalog.create(buyer, "product", "lc02-cup", "陶瓷杯", "native-mug", 5900, "CNY"));
        catalog.publish(buyer, "publish", product, 0);
        long sku = Long.parseLong(catalog.detail(tenant, product, false).skuId());
        assertEquals(1, sku);
        long warehouse = Long.parseLong(inventory.warehouse(buyer, "warehouse", "lc02", "可售仓"));
        long empty = Long.parseLong(inventory.warehouse(buyer, "empty", "lc02-empty", "无库存仓"));
        inventory.adjust(buyer, "stock", warehouse, sku, 5, "隔离验收");
        when(decoder.decode("buyer")).thenReturn(jwt("buyer", buyer.subject(), TENANT, "trade.member"));
        when(decoder.decode("other-buyer")).thenReturn(jwt("other-buyer", other.subject(), TENANT, "trade.member"));
        when(decoder.decode("foreign-buyer")).thenReturn(jwt("foreign-buyer", buyer.subject(), "906022", "trade.member"));
        when(decoder.decode("read-evidence")).thenReturn(jwt("read-evidence", SUBJECT, TENANT, "trade.checkout-pilot.order-evidence"));
        when(decoder.decode("wrong-evidence")).thenReturn(jwt("wrong-evidence", other.subject(), TENANT, "trade.checkout-pilot.order-evidence"));
        String evidence = CheckoutPilotOrderController.EVIDENCE_PATH.replace("{reference}", reference);
        assertEquals(404, http.send(api("read-evidence", evidence).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, sendOrder(reference, empty, "other-buyer", "untrusted").statusCode());
        assertEquals(403, sendOrder(reference, empty, "foreign-buyer", "untrusted").statusCode());
        assertEquals(409, sendOrder(reference, warehouse, "buyer", "different-native-revision").statusCode());
        catalog.unpublish(buyer, "unpublish", product, 1);
        catalog.edit(buyer, "correct-price", product, 2, "陶瓷杯", 5900, "CNY");
        catalog.publish(buyer, "republish", product, 3);
        STORE_VERSION.incrementAndGet();
        assertEquals(409, sendOrder(reference, warehouse, "buyer", "stale").statusCode());
        STORE_VERSION.decrementAndGet();
        var noStock = sendOrder(reference, empty, "buyer", "needs-stock");
        assertEquals("INSUFFICIENT_STOCK", json.readTree(noStock.body()).path("code").asString(), noStock.body());
        assertEquals(0, total("tc_quote", tenant));
        assertEquals(0, total("tc_order", tenant));

        var first = http.sendAsync(order(reference, warehouse, "buyer", "order-one"), HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(order(reference, warehouse, "buyer", "order-two"), HttpResponse.BodyHandlers.ofString());
        var opened = first.get(15, TimeUnit.SECONDS);
        var repeated = second.get(15, TimeUnit.SECONDS);
        assertEquals(200, opened.statusCode(), opened.body());
        assertEquals(200, repeated.statusCode(), repeated.body());
        var nativeOrder = json.readTree(opened.body());
        assertEquals(nativeOrder, json.readTree(repeated.body()));
        assertEquals(10800L, nativeOrder.path("payableAmount").asLong());
        assertEquals(10800L, nativeOrder.path("items").get(0).path("payableAmount").asLong());
        assertEquals("PENDING_PAYMENT", nativeOrder.path("status").asString());
        assertEquals(1, total("tc_quote", tenant));
        assertEquals(1, total("tc_order", tenant));
        assertEquals(2, inventory.balances(tenant, 0, 5).getFirst().reserved());
        assertEquals(409, sendOrder(reference, empty, "buyer", "order-one").statusCode());
        assertEquals(403, sendOrder(reference, warehouse, "other-buyer", "other-again").statusCode());

        assertEquals(403, http.send(api("buyer", evidence).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, http.send(api("wrong-evidence", evidence).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        var response = http.send(api("read-evidence", evidence).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        var facts = json.readTree(response.body());
        assertEquals(proofHash, facts.path("priceProofHash").asString());
        assertEquals(nativeOrder.path("orderId"), facts.path("orderId"));
        assertEquals("11800", facts.path("grossMinor").asString());
        assertEquals("1000", facts.path("discountMinor").asString());
        assertEquals("10800", facts.path("payableMinor").asString());
        assertFalse(facts.has("ownerRef"));

        String no = nativeOrder.path("orderNo").asString();
        assertTrue(no.matches("TRD-ORD-[0-9]{6}-[0-9]{10}"));
        assertEquals(no, facts.path("orderNo").asString());
        assertEquals(no, facts.path("businessNo").asString());
        var duplicate = post("buyer", "/api/v1/trade/orders", "regular-order", Map.of("quoteId",
            nativeOrder.path("quoteId").asString(), "warehouseId", Long.toString(warehouse)));
        assertEquals(409, http.send(duplicate, HttpResponse.BodyHandlers.ofString()).statusCode());
        var attempt = post("buyer", "/api/v1/trade/orders/" + no + "/payments", "payment", Map.of("channelCode", "TEST_A", "returnUrlId", "return"));
        var forbidden = http.send(attempt, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, forbidden.statusCode(), forbidden.body());
        assertEquals(OrderService.CHECKOUT_FUNDING_PENDING, json.readTree(forbidden.body()).path("code").asString());
        assertEquals(0, total("tc_payment_intent", tenant));
        assertFundingReceipt(reference, facts);
        var cancel = post("buyer", "/api/v1/trade/orders/" + no + ":cancel", "abort", Map.of("reason", "未预留预算"));
        assertEquals("CANCELLED", json.readTree(http.send(cancel, HttpResponse.BodyHandlers.ofString()).body()).path("status").asString());
        assertEquals(0, inventory.balances(tenant, 0, 5).getFirst().reserved());
        assertEquals(5, inventory.balances(tenant, 0, 5).getFirst().available());
    }

    private void assertFundingReceipt(String reference, tools.jackson.databind.JsonNode facts) throws Exception {
        when(decoder.decode("funding")).thenReturn(jwt("funding", SUBJECT, TENANT, "trade.checkout-pilot.funding"));
        when(decoder.decode("funding-other")).thenReturn(jwt("funding-other", "different-machine", TENANT, "trade.checkout-pilot.funding"));
        var claims = new TreeMap<String, String>();
        for (String key : java.util.List.of("tradeTenantId", "orderId", "scopeVersion", "storeId", "priceProofHash", "currency",
                "discountMinor", "payableMinor")) claims.put(key, facts.path(key).asString());
        claims.put("tradeQuoteRef", reference);
        claims.put("purpose", "marketing.checkout-funding/v1");
        claims.put("marketingTenantId", "lc02-pilot");
        claims.put("expiresAt", facts.path("orderExpiresAt").asString());
        claims.put("applicationId", UUID.randomUUID().toString());
        String path = CheckoutFundingReceiptController.PATH.replace("{reference}", reference);
        String token = sign(claims, "funding-test", FUNDING_KEY);
        assertEquals(403, http.send(post("funding-other", path, "wrong-actor", Map.of("fundingReceipt", token)),
            HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(409, http.send(post("funding", path, "wrong-signer", Map.of("fundingReceipt", sign(claims))),
            HttpResponse.BodyHandlers.ofString()).statusCode());
        var request = post("funding", path, "attach", Map.of("fundingReceipt", token));
        var first = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var attached = first.get(15, TimeUnit.SECONDS);
        var replay = second.get(15, TimeUnit.SECONDS);
        assertEquals(200, attached.statusCode(), attached.body());
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(json.readTree(attached.body()), json.readTree(replay.body()));
        claims.put("applicationId", UUID.randomUUID().toString());
        assertEquals(409, http.send(post("funding", path, "new-application",
            Map.of("fundingReceipt", sign(claims, "funding-test", FUNDING_KEY))), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private int total(String table, long tenant) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant);
    }

    private HttpResponse<String> sendOrder(String reference, long warehouse, String actor, String key) throws Exception {
        return http.send(order(reference, warehouse, actor, key), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest order(String reference, long warehouse, String actor, String key) {
        return post(actor, CheckoutPilotOrderController.CREATE_PATH, key, Map.of("tradeQuoteRef", reference, "warehouseId", Long.toString(warehouse),
            "address", Map.of("recipient", "验收人", "phone", "13900000001", "address", "隔离验收地址")));
    }

    private HttpRequest post(String actor, String path, String id, Map<String, ?> body) {
        return api(actor, path).header("Idempotency-Key", id).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }

    private HttpRequest.Builder api(String actor, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + actor);
    }

    private HttpResponse<String> freeze(String caller, String reference, String proof) throws Exception {
        return http.send(freezeRequest(caller, reference, proof), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest freezeRequest(String caller, String reference, String proof) {
        var path = CheckoutPilotPriceController.PATH.replace("{reference}", reference);
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + caller).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("checkoutQuoteToken", proof))))
            .build();
    }

    private static Map<String, String> claims(String reference, String sourceDigest) {
        return Map.ofEntries(Map.entry("purpose", "marketing.checkout-price/v1"), Map.entry("marketingTenantId", "lc02-pilot"),
            Map.entry("tradeTenantId", TENANT), Map.entry("tradeQuoteRef", reference), Map.entry("scopeVersion", "lc02-store-r1"),
            Map.entry("sourceDigest", sourceDigest), Map.entry("organizationId", "lc02-retail"), Map.entry("shopId", "lc02-store-a"),
            Map.entry("ownerRef", OWNER), Map.entry("cartDigest", "sha256:" + CheckoutPriceProof.sha256("CNY\nlc02-mug-a|lc02-mug-a|lc02-store-a|||2|5900|0|false\n")),
            Map.entry("marketingQuoteId", UUID.randomUUID().toString()), Map.entry("campaignId", "lc02-threshold"),
            Map.entry("generation", "1"), Map.entry("bundleId", "bundle"), Map.entry("bundleVersion", "1"),
            Map.entry("eligibleSkuId", "lc02-mug-a"), Map.entry("offerId", "offer"), Map.entry("benefitVersion", "benefit"),
            Map.entry("currency", "CNY"), Map.entry("grossMinor", "11800"), Map.entry("discountMinor", "1000"),
            Map.entry("payableMinor", "10800"), Map.entry("funderId", "lc02-mug-r1"), Map.entry("issuedAt", Instant.now().toString()),
            Map.entry("expiresAt", SOURCE_EXPIRY.toString()), Map.entry("lines", "lc02-mug-a~lc02-mug-a~2~5900~10800"));
    }

    private static String modified(Map<String, String> claims, String field, String value) throws GeneralSecurityException {
        var modified = new TreeMap<>(claims);
        modified.put(field, value);
        return sign(modified);
    }

    private static String sign(Map<String, String> claims) throws GeneralSecurityException {
        return sign(claims, "checkout-test", MARKETING_KEY);
    }

    private static String sign(Map<String, String> claims, String keyId, KeyPair key) throws GeneralSecurityException {
        var base64 = Base64.getUrlEncoder().withoutPadding();
        var canonical = new StringBuilder();
        new TreeMap<>(claims).forEach((name, value) -> canonical.append(name.length()).append(':').append(name)
            .append(value.length()).append(':').append(value));
        String signingInput = base64.encodeToString(keyId.getBytes(StandardCharsets.US_ASCII)) + "."
            + base64.encodeToString(canonical.toString().getBytes(StandardCharsets.US_ASCII));
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + base64.encodeToString(signer.sign());
    }

    private static KeyPair signingKey() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
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
                             "merchantId":"lc02-merchant","storeId":"lc02-store-a","storeVersion":%d,
                             "eligibleSkuId":"lc02-mug-a","commerceQuoteId":"%s","ownerRef":"%s",
                             "currency":"CNY","expiresAt":"%s","items":[{"skuId":"lc02-mug-a","skuRevision":1,
                             "channelPriceVersion":0,"quantity":2,"unitPriceMinor":"5900"}]}
                            """.formatted(TENANT, STORE_VERSION.get(), ELIGIBLE_QUOTE, OWNER, SOURCE_EXPIRY).getBytes(StandardCharsets.UTF_8);
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
