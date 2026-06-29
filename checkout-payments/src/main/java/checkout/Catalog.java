package checkout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hard-coded product catalog for the demo store (Part 4 says "some hard-coded
 * products or a basket summary" and explicitly "no need to use a database").
 *
 * <h2>Why pricing lives on the server</h2>
 * The browser only ever tells us <em>which</em> product and <em>how many</em>
 * (an id + quantity). It never sends a price. The authoritative price for every
 * product/currency is held here and the basket total is computed server-side in
 * {@link #priceBasket(JSONArray, String)} before we talk to Checkout.com. This
 * way a tampered front-end cannot change what the customer is actually charged.
 *
 * <h2>Minor units</h2>
 * All amounts are stored in the currency's <b>minor unit</b> (e.g. cents),
 * because that is exactly what the Checkout.com Payments API expects for the
 * {@code amount} and {@code unit_price} fields. EUR, HKD and USD are all
 * 2-decimal currencies, so 1999 == 19.99.
 */
public final class Catalog {

    private Catalog() { /* static-only holder */ }

    /**
     * A single sellable item. {@code priceByCurrency} maps an ISO-4217 code
     * (e.g. "EUR") to the price in that currency's minor unit.
     */
    public static final class Product {
        public final String id;
        public final String name;
        private final Map<String, Long> priceByCurrency;

        Product(String id, String name, Map<String, Long> priceByCurrency) {
            this.id = id;
            this.name = name;
            this.priceByCurrency = priceByCurrency;
        }

        /** Price in minor units for the requested currency (defaults to USD). */
        public long priceIn(String currency) {
            Long p = priceByCurrency.get(currency);
            if (p == null) {
                // Fallback keeps the demo robust if an unknown currency arrives.
                p = priceByCurrency.get("USD");
            }
            return p;
        }
    }

    /**
     * The store inventory. Order is preserved so the basket renders the same way
     * every time. Prices are illustrative but realistic for the three regions.
     */
    private static final Map<String, Product> PRODUCTS = new LinkedHashMap<>();

    private static void add(String id, String name, long eur, long hkd, long usd) {
        Map<String, Long> prices = new LinkedHashMap<>();
        prices.put("EUR", eur);
        prices.put("HKD", hkd);
        prices.put("USD", usd);
        PRODUCTS.put(id, new Product(id, name, prices));
    }

    static {
        //   id              display name              EUR    HKD     USD   (all minor units)
        add("CASE-CLEAR",    "Clear MagSafe Case",     1999,  15900,  2199);
        add("CASE-SILICONE", "Silicone Case \u00B7 Sage",  2499,  19900,  2699);
        add("CASE-LEATHER",  "Leather Case \u00B7 Olive",  2999,  23900,  3299);
    }

    /** Look up a product by id, or {@code null} if it is not in the catalog. */
    public static Product byId(String id) {
        return PRODUCTS.get(id);
    }

    /** All products, in display order (used to render the storefront). */
    public static List<Product> all() {
        return new ArrayList<>(PRODUCTS.values());
    }

    /**
     * Catalog as a JSON string for the browser to render the basket and show a
     * live total. This is for <b>display only</b>: the server re-prices every
     * basket in {@link #priceBasket(JSONArray, String)} before charging, so the
     * front-end can never dictate the real amount.
     *
     * <pre>[{"id","name","prices":{"EUR":..,"HKD":..,"USD":..}}, ...]</pre>
     */
    public static String frontendJson() {
        JSONArray arr = new JSONArray();
        for (Product p : PRODUCTS.values()) {
            JSONObject prices = new JSONObject();
            for (Map.Entry<String, Long> e : p.priceByCurrency.entrySet()) {
                prices.put(e.getKey(), e.getValue());
            }
            arr.put(new JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("prices", prices));
        }
        return arr.toString();
    }

    /**
     * Result of pricing a basket: the line {@code items} array (already in the
     * exact shape Checkout.com wants) and the {@code total} in minor units.
     */
    public static final class Basket {
        public final JSONArray items;
        public final long total;

        Basket(JSONArray items, long total) {
            this.items = items;
            this.total = total;
        }
    }

    /**
     * Validate and price a requested basket entirely on the server.
     *
     * @param requested array of {@code {"id": "...", "quantity": N}} from the browser
     * @param currency  ISO-4217 currency to price in (EUR / HKD / USD)
     * @return a {@link Basket} with Checkout.com-ready line items and the total
     * @throws IllegalArgumentException if the basket is empty / contains unknown
     *         products / has non-positive quantities (we never trust the client)
     */
    public static Basket priceBasket(JSONArray requested, String currency) {
        if (requested == null || requested.length() == 0) {
            throw new IllegalArgumentException("Basket is empty");
        }

        JSONArray items = new JSONArray();
        long total = 0;

        for (int i = 0; i < requested.length(); i++) {
            JSONObject line = requested.getJSONObject(i);
            String id = line.optString("id", null);
            int quantity = line.optInt("quantity", 0);

            Product product = byId(id);
            if (product == null) {
                throw new IllegalArgumentException("Unknown product: " + id);
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("Invalid quantity for " + id);
            }

            long unitPrice = product.priceIn(currency);
            total += unitPrice * quantity;

            // Line item shape per Checkout.com "items" spec: name, quantity,
            // unit_price (minor units) and our own reference (the product id).
            items.put(new JSONObject()
                    .put("name", product.name)
                    .put("reference", product.id)
                    .put("quantity", quantity)
                    .put("unit_price", unitPrice));
        }

        return new Basket(items, total);
    }
}
