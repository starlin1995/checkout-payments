package checkout;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.json.JSONArray;
import org.json.JSONObject;

import view.RenderUtil;

import static spark.Spark.get;
import static spark.Spark.port;
import static spark.Spark.post;
import static spark.Spark.staticFiles;

import com.google.gson.Gson;

/**
 * Checkout.com Flow demo — server side (Java + Spark).
 *
 * <p>This is the converted version of the original Tony's PSP integration sample.
 * The HTTP server (Spark), the Jinjava template helper and the JSON libraries are all
 * kept </p>
 *
 * <h2>Integration method (Part 4)</h2>
 * We use <b>Checkout.com Flow</b>. The customer's card details are entered into
 * Flow's hosted fields (an iframe served by Checkout.com), so raw card data
 * never reaches this server — that is what keeps the merchant in the lightest
 * PCI scope (SAQ A) while still showing the card form on our own page. Flow also
 * renders wallet buttons such as <b>Apple Pay</b> automatically on supported
 * devices (relevant because most shoppers here are on iPhone).
 *
 * <h2>Request flow</h2>
 * <ol>
 *   <li>Browser loads {@code GET /} (the storefront) and we inject the public key.</li>
 *   <li>Browser posts the chosen region + basket to {@code POST /create-payment-session}.</li>
 *   <li>We price the basket server-side and ask Checkout.com to create a
 *       <em>payment session</em>; the session token is returned to the browser.</li>
 *   <li>Flow mounts using that session and tokenises + submits the payment.</li>
 *   <li>On completion the browser lands on {@code GET /success} (or {@code /failure})
 *       and we confirm the outcome via {@code GET /payment-status}.</li>
 * </ol>
 */
public class Application {

    private static final String CONFIG_FILE = "config.properties";

    public static void main(String[] args) throws IOException {

        // Same server setup as the original sample.
        port(8081);
        staticFiles.location("/static"); // serves /css, /js, /img from resources/static

        Properties prop = readConfigFile();

        // --- Checkout.com configuration (see config.properties) ---
        final String publicKey = prop.getProperty("CHECKOUT_PUBLIC_KEY", "");
        final String secretKey = prop.getProperty("CHECKOUT_SECRET_KEY", "");
        final String processingChannelId = prop.getProperty("CHECKOUT_PROCESSING_CHANNEL_ID", "");
        final String webhookKey = prop.getProperty("CHECKOUT_WEBHOOK_KEY", "");
        final String apiBaseUrl = prop.getProperty("CHECKOUT_API_BASE_URL", "https://api.sandbox.checkout.com");
        final String appBaseUrl = prop.getProperty("APP_BASE_URL", "http://localhost:8081");

        // One reusable REST client for all Checkout.com calls.
        CheckoutService checkout = new CheckoutService(apiBaseUrl, secretKey, processingChannelId);

        Gson gson = new Gson();

        // =================================================================
        // Storefront
        // =================================================================
        // Render the checkout page and pass the PUBLIC key down to the browser
        // (the public key is safe to expose; the secret key never leaves here).
        get("/", (req, res) -> {
            Map<String, Object> context = new HashMap<>();
            context.put("publicKey", publicKey);
            context.put("catalogJson", Catalog.frontendJson());
            return RenderUtil.render(context, "templates/checkout.html");
        });

        // =================================================================
        // Create a payment session  (called by the Flow front-end)
        // =================================================================
        // Body: { region, locale, name?, email?, force3ds?, items:[{id, quantity}] }
        // We never trust a price from the browser — only the product id +
        // quantity. The authoritative price/total is computed in Catalog.
        post("/create-payment-session", (req, res) -> {
            res.type("application/json");
            try {
                JSONObject in = new JSONObject(req.body());

                String region = in.optString("region", "NL");       // HK | NL | US
                // locale is applied client-side when mounting Flow; no server use.
                String name = in.optString("name", null);
                String email = in.optString("email", null);
                boolean force3ds = in.optBoolean("force3ds", false);
                JSONArray requested = in.optJSONArray("items");

                // Map the storefront region to an ISO currency + billing country.
                String currency;
                String country;
                switch (region) {
                    case "HK": currency = "HKD"; country = "HK"; break;
                    case "US": currency = "USD"; country = "US"; break;
                    case "NL":
                    default:   currency = "EUR"; country = "NL"; break;
                }

                // Price + validate the basket on the server.
                Catalog.Basket basket = Catalog.priceBasket(requested, currency);

                // A human-readable reference helps reconciliation in the dashboard.
                String reference = "ORD-" + System.currentTimeMillis();

                // Redirect targets used by methods that leave the page (3DS / wallets).
                String successUrl = appBaseUrl + "/success";
                String failureUrl = appBaseUrl + "/failure";

                JSONObject session = checkout.createPaymentSession(
                        basket.total, currency, country, reference,
                        name, email, basket.items, force3ds,
                        successUrl, failureUrl);

                // Bubble up a non-2xx from the API as an error to the browser.
                if (session.has("http_status") && session.getInt("http_status") >= 400) {
                    res.status(session.getInt("http_status"));
                }
                // Return the session object unchanged — Flow consumes it as-is.
                return session.toString();

            } catch (IllegalArgumentException badBasket) {
                // Client sent an empty/unknown/invalid basket.
                res.status(400);
                return new JSONObject().put("error", badBasket.getMessage()).toString();
            } catch (Exception e) {
                e.printStackTrace();
                res.status(502);
                return new JSONObject().put("error", "payment_session_failed").toString();
            }
        });

        // =================================================================
        // Result pages (Flow redirects here for redirect-based methods)
        // =================================================================
        get("/success", (req, res) -> {
            Map<String, Object> context = new HashMap<>();
            return RenderUtil.render(context, "templates/success.html");
        });

        get("/failure", (req, res) -> {
            Map<String, Object> context = new HashMap<>();
            return RenderUtil.render(context, "templates/failure.html");
        });

        // =================================================================
        // Confirm a payment's outcome  (used by the success page)
        // =================================================================
        // Returns a small JSON object via gson (mirrors the original
        // /session-status route which also returned a Map + gson transformer).
        get("/payment-status", (req, res) -> {
            Map<String, Object> map = new HashMap<>();
            try {
                String paymentId = req.queryParams("payment_id");
                if (paymentId == null || paymentId.isBlank()) {
                    res.status(400);
                    map.put("error", "missing payment_id");
                    return map;
                }

                JSONObject payment = checkout.getPayment(paymentId);

                map.put("id", payment.optString("id", paymentId));
                map.put("status", payment.optString("status", "Unknown"));
                map.put("reference", payment.optString("reference", ""));
                // amount/currency may be absent on some error payloads — guard them.
                if (!payment.isNull("amount")) map.put("amount", payment.getLong("amount"));
                if (!payment.isNull("currency")) map.put("currency", payment.getString("currency"));
            } catch (Exception e) {
                e.printStackTrace();
                res.status(502);
                map.put("error", "status_lookup_failed");
            }
            return map;
        }, gson::toJson);

        // =================================================================
        // Refund a captured payment  (Part 5)
        // =================================================================
        // Body: { payment_id, amount }  — amount is in MINOR units; <= 0 => full refund.
        post("/refund", (req, res) -> {
            res.type("application/json");
            try {
                JSONObject in = new JSONObject(req.body());
                String paymentId = in.optString("payment_id", null);
                long amount = in.optLong("amount", 0); // minor units; 0 => full refund

                if (paymentId == null || paymentId.isBlank()) {
                    res.status(400);
                    return new JSONObject().put("error", "missing payment_id").toString();
                }

                String reference = "REF-" + System.currentTimeMillis();
                JSONObject refund = checkout.refundPayment(paymentId, amount, reference);

                if (refund.has("http_status") && refund.getInt("http_status") >= 400) {
                    res.status(refund.getInt("http_status"));
                }
                return refund.toString();
            } catch (Exception e) {
                e.printStackTrace();
                res.status(502);
                return new JSONObject().put("error", "refund_failed").toString();
            }
        });

        // =================================================================
        // Webhook receiver  (Part 5)
        // =================================================================
        // Checkout.com POSTs event notifications here (payment_approved,
        // payment_captured, payment_declined, ...). We verify the signature and
        // ALWAYS return 200 quickly so the platform does not retry needlessly.
        post("/webhooks", (req, res) -> {
            String rawBody = req.body();
            String signature = req.headers("cko-signature");

            // If a signing key is configured, reject anything that fails the HMAC
            // check. If no key is set (e.g. quick local testing), we skip the
            // check but still process the event.
            if (webhookKey != null && !webhookKey.isBlank()) {
                boolean ok = CheckoutService.verifyWebhookSignature(rawBody, signature, webhookKey);
                if (!ok) {
                    res.status(401);
                    return "invalid signature";
                }
            }

            try {
                JSONObject event = new JSONObject(rawBody);
                String type = event.optString("type", "unknown");
                String paymentId = event.optJSONObject("data") != null
                        ? event.getJSONObject("data").optString("id", "")
                        : "";

                switch (type) {
                    case "payment_approved":
                        System.out.println("[webhook] payment approved: " + paymentId);
                        break;
                    case "payment_captured":
                        System.out.println("[webhook] payment captured: " + paymentId);
                        break;
                    case "payment_declined":
                        System.out.println("[webhook] payment declined: " + paymentId);
                        break;
                    default:
                        System.out.println("[webhook] received event: " + type + " " + paymentId);
                }
            } catch (Exception e) {
                // Even on a parse problem we acknowledge receipt (a 200) so the
                // event is not retried forever; we just log the issue.
                e.printStackTrace();
            }

            res.status(200);
            return "ok";
        });

        System.out.println("Checkout.com demo running on " + appBaseUrl);
    }

    /**
     * Load config.properties from the working directory (run from the project
     * root, e.g. via {@code ./gradlew run}). Unchanged from the original sample.
     */
    private static Properties readConfigFile() {
        Properties prop = new Properties();
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(CONFIG_FILE))) {
            prop.load(in);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return prop;
    }
}
