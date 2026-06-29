package checkout;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * CheckoutService — a thin client around the Checkout.com REST API.
 *
 * <h2>Why raw HTTP instead of the SDK?</h2>
 * The original project made its API calls over plain HTTP. We keep that exact
 * style here using the JDK's built-in {@link java.net.http.HttpClient}
 * (available since Java 11), so:
 * <ul>
 *   <li>no extra Gradle dependency is required;</li>
 *   <li>every request/response body is fully visible and easy to debug;</li>
 *   <li>you can drop in the official SDK later — the JSON payloads are identical.</li>
 * </ul>
 *
 * <h2>Security model (PCI)</h2>
 * Every call here is authenticated with the <b>SECRET</b> key via the
 * {@code Authorization: Bearer sk_sbox_...} header. The secret key lives on the
 * server only. The browser receives just the <b>PUBLIC</b> key (pk_sbox_...) and
 * the payment-session object, which is exactly what Checkout.com Flow needs to
 * collect card data inside its own hosted fields. Raw card numbers therefore
 * never reach our backend, keeping the merchant in PCI <b>SAQ A</b> scope.
 */
public class CheckoutService {

    /** Base API host, e.g. https://api.sandbox.checkout.com (or api.checkout.com in production). */
    private final String baseUrl;
    /** Secret API key — used for server-to-server authentication. Never sent to the browser. */
    private final String secretKey;
    /** Processing channel id (pc_...) — routes the payment through the right entity/MID. */
    private final String processingChannelId;

    /** One reusable HTTP client for the whole app (enables connection pooling). */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public CheckoutService(String baseUrl, String secretKey, String processingChannelId) {
        this.baseUrl = baseUrl;
        this.secretKey = secretKey;
        this.processingChannelId = processingChannelId;
    }

    // =====================================================================
    // 1) Payment Sessions  (the server-side step that powers Flow)
    // =====================================================================

    /**
     * Create a <b>Payment Session</b> — required to render Checkout.com Flow.
     *
     * <p>Flow (the low-code, PCI-friendly UI component) is initialised in the
     * browser with the PUBLIC key plus the session object returned here. Card
     * details are captured by Flow's hosted fields, so the PAN/CVV never touch
     * our server. After the shopper pays, Flow either fires an
     * {@code onPaymentCompleted} callback (inline approval) or redirects to the
     * {@code success_url}/{@code failure_url} (e.g. after a 3DS challenge or an
     * Apple&nbsp;Pay sheet).</p>
     *
     * @param amount       total in the currency's <b>minor</b> units (e.g. 1999 = €19.99)
     * @param currency     ISO-4217 code, e.g. {@code "EUR"} (Netherlands) or {@code "HKD"} (Hong Kong)
     * @param country      billing country ISO-2, e.g. {@code "NL"} / {@code "HK"} — drives local methods, FX and SCA rules
     * @param reference    merchant order reference shown in the dashboard
     * @param customerName optional cardholder name forwarded to the API (Part 5); {@code null} to skip
     * @param customerEmail optional customer email forwarded to the API (Part 5); {@code null} to skip
     * @param items        basket line items (name/quantity/unit_price) for the order summary
     * @param require3ds   force a 3-D Secure challenge regardless of amount (Part 5);
     *                     3DS also turns on automatically when amount &gt; 2000 minor units (20.00)
     * @param successUrl   where Flow returns the shopper after an approved payment
     * @param failureUrl   where Flow returns the shopper after a declined/abandoned payment
     * @return the raw payment-session JSON (passed straight to Flow in the browser)
     */
    public JSONObject createPaymentSession(long amount, String currency, String country,
                                           String reference, String customerName, String customerEmail,
                                           JSONArray items, boolean require3ds,
                                           String successUrl, String failureUrl) throws Exception {
        // Build the request body. Field names follow Checkout.com's
        // "Create a payment session" API reference.
        JSONObject body = new JSONObject();
        body.put("amount", amount);
        body.put("currency", currency);
        body.put("reference", reference);
        body.put("display_name", "iPhone Case Store"); // shown on wallet sheets / 3DS pages

        // Billing country influences available local payment methods, SCA/3DS
        // requirements and any currency conversion.
        body.put("billing", new JSONObject()
                .put("address", new JSONObject().put("country", country)));

        // (Part 5) Optional customer block: collect the cardholder name + email
        // on our page and forward them to the payment API.
        boolean hasName = customerName != null && !customerName.isBlank();
        boolean hasEmail = customerEmail != null && !customerEmail.isBlank();
        if (hasName || hasEmail) {
            JSONObject customer = new JSONObject();
            if (hasEmail) customer.put("email", customerEmail);
            if (hasName) customer.put("name", customerName);
            body.put("customer", customer);
        }

        // Line items render the order/basket summary in the dashboard.
        if (items != null && items.length() > 0) {
            body.put("items", items);
        }

        // Redirect targets for methods that leave the page (3DS, Apple Pay, etc.).
        body.put("success_url", successUrl);
        body.put("failure_url", failureUrl);

        // Route through the correct processing channel when configured.
        if (processingChannelId != null && !processingChannelId.isBlank()) {
            body.put("processing_channel_id", processingChannelId);
        }

        // 3DS policy: turn on 3-D Secure automatically for higher-value baskets,
        // and still allow it to be forced on demand (Part 5). EUR, HKD and USD
        // are all 2-decimal currencies, so a 20.00 threshold == 2000 minor units.
        // Below the threshold we leave "3ds" unset so low-value payments stay
        // frictionless (the issuer/SCA rules may still step up where required).
        final long threeDsThresholdMinor = 2000; // 20.00 in EUR / HKD / USD
        boolean enable3ds = require3ds || amount > threeDsThresholdMinor;
        if (enable3ds) {
            body.put("3ds", new JSONObject().put("enabled", true));
        }

        return post("/payment-sessions", body);
    }

    // =====================================================================
    // 2) Retrieve a payment (to confirm the final outcome)
    // =====================================================================

    /**
     * Retrieve a payment by id to confirm its status
     * ({@code Authorized} / {@code Captured} / {@code Declined} ...).
     */
    public JSONObject getPayment(String paymentId) throws Exception {
        return get("/payments/" + paymentId);
    }

    // =====================================================================
    // 3) Refunds (Part 5)
    // =====================================================================

    /**
     * Refund a captured payment.
     *
     * @param paymentId the payment to refund (pay_...)
     * @param amount    minor units to refund; pass {@code <= 0} for a <b>full</b> refund
     * @param reference our own refund reference (handy for reconciliation)
     */
    public JSONObject refundPayment(String paymentId, long amount, String reference) throws Exception {
        JSONObject body = new JSONObject();
        if (amount > 0) body.put("amount", amount);     // omit "amount" => full refund
        if (reference != null) body.put("reference", reference);
        return post("/payments/" + paymentId + "/refunds", body);
    }

    // =====================================================================
    // 4) Webhook signature verification (Part 5)
    // =====================================================================

    /**
     * Verify an incoming webhook came from Checkout.com.
     *
     * <p>Checkout.com signs the <b>raw</b> request body with your webhook signing
     * key using HMAC-SHA256 and sends the lower-case hex digest in the
     * {@code cko-signature} header. We recompute the digest locally and compare
     * it in constant time. A match proves the event is authentic and untampered.</p>
     *
     * @param rawBody         the exact request body bytes as received (do not re-serialize)
     * @param signatureHeader value of the {@code cko-signature} header
     * @param signingKey      the webhook signing key from the dashboard
     * @return {@code true} only if the signature is valid
     */
    public static boolean verifyWebhookSignature(String rawBody, String signatureHeader, String signingKey) {
        if (rawBody == null || signatureHeader == null || signingKey == null || signingKey.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            return constantTimeEquals(toHex(digest), signatureHeader.trim());
        } catch (Exception e) {
            return false;
        }
    }

    // =====================================================================
    // Low-level HTTP helpers
    // =====================================================================

    private JSONObject post(String path, JSONObject body) throws Exception {
        HttpRequest req = baseRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return send(req, "POST " + path);
    }

    private JSONObject get(String path) throws Exception {
        HttpRequest req = baseRequest(path).GET().build();
        return send(req, "GET " + path);
    }

    /** Shared request builder: target URL + auth + Accept header. */
    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + secretKey) // SECRET key auth
                .header("Accept", "application/json");
    }

    /**
     * Send the request and normalise the result.
     * <p>On 2xx we return the parsed JSON body. On any other status we still
     * return a JSONObject, but tagged with {@code http_status} and the API's
     * error payload, so callers can surface a sensible message and the logs show
     * exactly what Checkout.com rejected.</p>
     */
    private JSONObject send(HttpRequest req, String label) throws Exception {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = res.statusCode();
        String text = res.body();
        System.out.println("[Checkout.com] " + label + " -> HTTP " + code);

        if (code >= 200 && code < 300) {
            // Some endpoints can return an empty body (e.g. 202 Accepted).
            return (text == null || text.isBlank()) ? new JSONObject() : new JSONObject(text);
        }

        System.out.println("[Checkout.com] " + label + " error body: " + text);
        JSONObject err = new JSONObject();
        err.put("http_status", code);
        try {
            err.put("error", new JSONObject(text));
        } catch (Exception ignore) {
            err.put("error", text == null ? "" : text);
        }
        return err;
    }

    // ---- tiny utilities (no extra dependencies) ----

    /** Lower-case hex encoding, used to compare against the cko-signature header. */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Length-constant string comparison to avoid timing side-channels. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
