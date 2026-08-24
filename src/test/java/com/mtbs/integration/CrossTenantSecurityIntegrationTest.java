package com.mtbs.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mtbs.app.MultiTenantBillingSystemApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the P0 finding surfaced during the pre-deployment cross-tenant
 * security audit (docs/MULTI_TENANT_SECURITY_AUDIT.md): unlike {@link MultiTenancyIntegrationTest},
 * which manipulates {@code TenantContext} directly and never exercises HTTP or JWT auth, this test
 * drives two real tenants entirely through the public REST API with real cookie-based JWT sessions —
 * the same path a real attacker would use.
 *
 * <p>Fresh tenant schemas have colliding auto-increment primary keys (both tenants' first customer is
 * id=1, etc.), so a raw "does the attacker's request against victim's ID return 403/404" assertion is
 * invalid: schema-per-tenant routing means that request actually and correctly resolves to the
 * attacker's own same-numbered local record. This test instead verifies the real security properties:
 * (1) content-based leak detection — a victim-only marker string must never appear in the attacker's
 * response, and (2) integrity-based mutation detection — the victim's own session must see byte-for-byte
 * unchanged state immediately before vs. immediately after any attacker mutation attempt.
 */
@SpringBootTest(classes = MultiTenantBillingSystemApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrossTenantSecurityIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    private Tenant tenantA;
    private Tenant tenantB;

    /** A single tenant's session: its access-token cookie plus the resource ids it created. */
    private static final class Tenant {
        String accessTokenCookie;
        String marker;
        long customerId;
        long productId;
        long billId;
        long attachmentId;
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/v1" + path;
    }

    private HttpHeaders authHeaders(Tenant t) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, t.accessTokenCookie);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).path("data");
    }

    // ── Setup: two independent tenants, each with a full set of resources ──────────

    @BeforeAll
    void setUpTenants() throws Exception {
        tenantA = setUpTenant("A");
        tenantB = setUpTenant("B");
    }

    private Tenant setUpTenant(String label) throws Exception {
        Tenant t = new Tenant();
        t.marker = "SECRET-" + label + "-" + UUID.randomUUID();

        String email = "cross-tenant-audit-" + label.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        String signupBody = """
                {"name":"Security Audit Shop %s","email":"%s","password":"TestPass@2026"}
                """.formatted(label, email);
        HttpHeaders signupHeaders = new HttpHeaders();
        signupHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> signup = restTemplate.exchange(
                url("/auth/signup"), HttpMethod.POST, new HttpEntity<>(signupBody, signupHeaders), String.class);
        assertThat(signup.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        t.accessTokenCookie = extractAccessTokenCookie(signup.getHeaders());
        assertThat(t.accessTokenCookie).as("signup must set an access_token cookie").isNotBlank();

        HttpHeaders auth = authHeaders(t);

        String customerBody = """
                {"name":"%s Customer","email":"secret.%s@example.com","phone":"9000000001","address":"%s confidential address"}
                """.formatted(t.marker, UUID.randomUUID(), t.marker);
        ResponseEntity<String> customer = restTemplate.exchange(
                url("/customers"), HttpMethod.POST, new HttpEntity<>(customerBody, auth), String.class);
        t.customerId = data(customer).path("id").asLong();

        String productBody = """
                {"name":"%s Product","price":111,"taxPercentage":18}
                """.formatted(t.marker);
        ResponseEntity<String> product = restTemplate.exchange(
                url("/products"), HttpMethod.POST, new HttpEntity<>(productBody, auth), String.class);
        t.productId = data(product).path("id").asLong();

        String billBody = """
                {"customerId":%d,"items":[{"productId":%d,"quantity":1}],"notes":"%s bill notes"}
                """.formatted(t.customerId, t.productId, t.marker);
        ResponseEntity<String> bill = restTemplate.exchange(
                url("/business-invoices"), HttpMethod.POST, new HttpEntity<>(billBody, auth), String.class);
        t.billId = data(bill).path("id").asLong();
        restTemplate.exchange(
                url("/business-invoices/" + t.billId + "/finalize"), HttpMethod.POST, new HttpEntity<>(auth), String.class);

        BigDecimal totalAmount = new BigDecimal(data(bill).path("totalAmount").asText("0"));
        String paymentBody = """
                {"amount":%s,"method":"CASH","notes":"%s payment note"}
                """.formatted(totalAmount, t.marker);
        restTemplate.exchange(
                url("/business-payments/" + t.billId), HttpMethod.POST, new HttpEntity<>(paymentBody, auth), String.class);

        // 1x1 red PNG
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
        multipart.add("file", new ByteArrayResource(png) {
            @Override
            public String getFilename() {
                return t.marker + "_logo.png";
            }
        });
        HttpHeaders multipartHeaders = new HttpHeaders();
        multipartHeaders.set(HttpHeaders.COOKIE, t.accessTokenCookie);
        multipartHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> attachment = restTemplate.exchange(
                url("/attachments?purpose=LOGO"), HttpMethod.POST,
                new HttpEntity<>(multipart, multipartHeaders), String.class);
        t.attachmentId = data(attachment).path("id").asLong();

        String settingsBody = """
                {"businessName":"%s Real Business Name","logoAttachmentId":%d,"showLogo":true}
                """.formatted(t.marker, t.attachmentId);
        restTemplate.exchange(
                url("/shop-settings"), HttpMethod.PUT, new HttpEntity<>(settingsBody, auth), String.class);

        return t;
    }

    private String extractAccessTokenCookie(HttpHeaders headers) {
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) return null;
        for (String cookie : setCookies) {
            if (cookie.startsWith("access_token=")) {
                return cookie.substring(0, cookie.indexOf(';'));
            }
        }
        return null;
    }

    // ── Attacks: run in both directions ─────────────────────────────────────────

    @Test
    @Order(1)
    void tenantA_cannotReadWriteOrDeleteTenantBsResources() throws Exception {
        assertNoCrossTenantAccess(tenantA, tenantB);
    }

    @Test
    @Order(2)
    void tenantB_cannotReadWriteOrDeleteTenantAsResources() throws Exception {
        assertNoCrossTenantAccess(tenantB, tenantA);
    }

    private void assertNoCrossTenantAccess(Tenant attacker, Tenant victim) throws Exception {
        HttpHeaders attackerAuth = authHeaders(attacker);
        HttpHeaders victimAuth = authHeaders(victim);

        // ── Customer: GET never leaks, PUT/DELETE never mutate victim's own record ──
        ResponseEntity<String> getCustomer = restTemplate.exchange(
                url("/customers/" + victim.customerId), HttpMethod.GET, new HttpEntity<>(attackerAuth), String.class);
        assertThat(getCustomer.getBody()).as("GET victim customer must not leak victim marker")
                .doesNotContain(victim.marker);

        JsonNode victimCustomerBefore = mapper.readTree(fetchBody(url("/customers/" + victim.customerId), victimAuth)).path("data");
        restTemplate.exchange(url("/customers/" + victim.customerId), HttpMethod.PUT,
                new HttpEntity<>("{\"name\":\"HACKED\"}", attackerAuth), String.class);
        JsonNode victimCustomerAfterPut = mapper.readTree(fetchBody(url("/customers/" + victim.customerId), victimAuth)).path("data");
        assertThat(victimCustomerAfterPut).as("victim's customer must be unchanged after attacker PUT")
                .isEqualTo(victimCustomerBefore);

        restTemplate.exchange(url("/customers/" + victim.customerId), HttpMethod.DELETE,
                new HttpEntity<>(attackerAuth), Void.class);
        String victimCustomerAfterDelete = fetchBody(url("/customers/" + victim.customerId), victimAuth);
        assertThat(victimCustomerAfterDelete).as("victim's customer must still exist after attacker DELETE")
                .contains(victim.marker);

        // ── Product: GET never leaks; DELETE (deactivate) never affects victim's own record ──
        ResponseEntity<String> getProduct = restTemplate.exchange(
                url("/products/" + victim.productId), HttpMethod.GET, new HttpEntity<>(attackerAuth), String.class);
        assertThat(getProduct.getBody()).as("GET victim product must not leak victim marker")
                .doesNotContain(victim.marker);

        boolean victimProductActiveBefore = data(restTemplate.exchange(
                url("/products/" + victim.productId), HttpMethod.GET, new HttpEntity<>(victimAuth), String.class))
                .path("isActive").asBoolean();
        restTemplate.exchange(url("/products/" + victim.productId), HttpMethod.DELETE,
                new HttpEntity<>(attackerAuth), Void.class);
        boolean victimProductActiveAfter = data(restTemplate.exchange(
                url("/products/" + victim.productId), HttpMethod.GET, new HttpEntity<>(victimAuth), String.class))
                .path("isActive").asBoolean();
        assertThat(victimProductActiveAfter).as("attacker DELETE must not deactivate victim's own product")
                .isEqualTo(victimProductActiveBefore);

        // ── Bill: GET, list, and PDF preview/download must never leak victim's marker ──
        ResponseEntity<String> getBill = restTemplate.exchange(
                url("/business-invoices/" + victim.billId), HttpMethod.GET, new HttpEntity<>(attackerAuth), String.class);
        assertThat(getBill.getBody()).as("GET victim bill must not leak victim marker")
                .doesNotContain(victim.marker);

        ResponseEntity<String> billList = restTemplate.exchange(
                url("/business-invoices?size=200"), HttpMethod.GET, new HttpEntity<>(attackerAuth), String.class);
        assertThat(billList.getBody()).as("attacker's own bill list must not include victim's marker")
                .doesNotContain(victim.marker);

        ResponseEntity<byte[]> preview = restTemplate.exchange(
                url("/business-invoices/" + victim.billId + "/preview"), HttpMethod.GET,
                new HttpEntity<>(attackerAuth), byte[].class);
        if (preview.getStatusCode() == HttpStatus.OK && preview.getBody() != null) {
            assertThat(new String(preview.getBody(), java.nio.charset.StandardCharsets.ISO_8859_1))
                    .as("victim's bill PDF preview must not contain victim's marker")
                    .doesNotContain(victim.marker);
        }

        // ── Payment: recording a fraudulent payment must not move victim's outstanding balance ──
        BigDecimal outstandingBefore = new BigDecimal(mapper.readTree(fetchBody(
                url("/business-payments/invoice/" + victim.billId + "/outstanding"), victimAuth))
                .path("data").asText());
        restTemplate.exchange(url("/business-payments/" + victim.billId), HttpMethod.POST,
                new HttpEntity<>("{\"amount\":1,\"method\":\"CASH\",\"notes\":\"fraud\"}", attackerAuth), String.class);
        BigDecimal outstandingAfter = new BigDecimal(mapper.readTree(fetchBody(
                url("/business-payments/invoice/" + victim.billId + "/outstanding"), victimAuth))
                .path("data").asText());
        assertThat(outstandingAfter).as("attacker's payment attempt must not change victim's outstanding balance")
                .isEqualByComparingTo(outstandingBefore);

        // ── Attachment: metadata GET never leaks; DELETE never removes victim's own file ──
        ResponseEntity<String> attachmentMeta = restTemplate.exchange(
                url("/attachments/" + victim.attachmentId), HttpMethod.GET, new HttpEntity<>(attackerAuth), String.class);
        if (attachmentMeta.getStatusCode() == HttpStatus.OK) {
            assertThat(attachmentMeta.getBody()).as("attacker must not see victim's attachment filename")
                    .doesNotContain(victim.marker);
        }

        ResponseEntity<byte[]> fileBefore = restTemplate.exchange(
                url("/attachments/" + victim.attachmentId + "/file"), HttpMethod.GET,
                new HttpEntity<>(victimAuth), byte[].class);
        restTemplate.exchange(url("/attachments/" + victim.attachmentId), HttpMethod.DELETE,
                new HttpEntity<>(attackerAuth), Void.class);
        ResponseEntity<byte[]> fileAfter = restTemplate.exchange(
                url("/attachments/" + victim.attachmentId + "/file"), HttpMethod.GET,
                new HttpEntity<>(victimAuth), byte[].class);
        assertThat(fileAfter.getStatusCode()).as("victim's own attachment access must be unaffected by attacker's DELETE")
                .isEqualTo(fileBefore.getStatusCode());

        // ── Shop settings: caller's own GET must never return the other tenant's business name ──
        ResponseEntity<String> settings = restTemplate.exchange(
                url("/shop-settings"), HttpMethod.GET, new HttpEntity<>(attackerAuth), String.class);
        assertThat(settings.getBody()).as("attacker's own shop-settings must not contain victim's business name")
                .doesNotContain(victim.marker);
    }

    private String fetchBody(String url, HttpHeaders headers) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
    }
}
