/*
 * Checkout.com Flow — front-end integration.
 *
 * Responsibilities:
 *   1. Render the basket and keep a live total (display only).
 *   2. On "Continue to payment", POST the basket to our server, which creates a
 *      Checkout.com *payment session* and returns it.
 *   3. Mount Flow with that session. Flow shows the secure card fields (and
 *      Apple Pay / wallets where available) and renders its OWN pay button, so
 *      card data is captured inside Checkout.com's iframe — never by us.
 *
 * The amount is decided by the server (see Catalog.java). The price map below is
 * only used to show the basket and a running total in the UI.
 */

// ---- Injected by the server ------------------------------------------------
const cfg = JSON.parse(
  document.getElementById("cko-config").textContent || "{}"
);
const publicKey = cfg.publicKey || "";
const catalog = cfg.catalog || [];

// ---- Display helpers -------------------------------------------------------
const currencyByRegion = { NL: "EUR", HK: "HKD", US: "USD" };
const currencySymbol = { EUR: "\u20AC", HKD: "HK$", USD: "$" };

function selectedRegion() {
  return document.getElementById("region").value;
}
function selectedCurrency() {
  return currencyByRegion[selectedRegion()] || "EUR";
}
function priceOf(product, currency) {
  // Fall back to USD if a currency is somehow missing.
  return product.prices[currency] != null ? product.prices[currency] : product.prices.USD;
}
function formatMinor(minor, currency) {
  return currencySymbol[currency] + (minor / 100).toFixed(2);
}

// ---- Basket state ----------------------------------------------------------
const quantities = {};
catalog.forEach((p) => { quantities[p.id] = 0; });
if (catalog.length) quantities[catalog[0].id] = 1; // start with one item

function renderBasket() {
  const currency = selectedCurrency();
  const container = document.getElementById("basket");
  container.innerHTML = "";

  catalog.forEach((p) => {
    const row = document.createElement("div");
    row.className = "basket-row";

    const item = document.createElement("div");
    item.className = "item";
    const name = document.createElement("div");
    name.className = "item-name";
    name.textContent = p.name;
    const price = document.createElement("div");
    price.className = "item-price";
    price.textContent = formatMinor(priceOf(p, currency), currency);
    item.appendChild(name);
    item.appendChild(price);

    const qty = document.createElement("div");
    qty.className = "qty";
    const minus = makeStepper("\u2212", p.id, -1);
    const value = document.createElement("span");
    value.className = "qty-value";
    value.id = "qty-" + p.id;
    value.textContent = String(quantities[p.id]);
    const plus = makeStepper("+", p.id, 1);
    qty.appendChild(minus);
    qty.appendChild(value);
    qty.appendChild(plus);

    row.appendChild(item);
    row.appendChild(qty);
    container.appendChild(row);
  });

  renderTotal();
}

function makeStepper(label, id, delta) {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.textContent = label;
  btn.setAttribute("aria-label", delta > 0 ? "Increase quantity" : "Decrease quantity");
  btn.addEventListener("click", () => {
    quantities[id] = Math.max(0, (quantities[id] || 0) + delta);
    document.getElementById("qty-" + id).textContent = String(quantities[id]);
    renderTotal();
  });
  return btn;
}

function renderTotal() {
  const currency = selectedCurrency();
  let total = 0;
  catalog.forEach((p) => { total += priceOf(p, currency) * (quantities[p.id] || 0); });
  document.getElementById("total").textContent = formatMinor(total, currency);
}

function basketItems() {
  return catalog
    .filter((p) => (quantities[p.id] || 0) > 0)
    .map((p) => ({ id: p.id, quantity: quantities[p.id] }));
}

// ---- Payment flow ----------------------------------------------------------
const message = document.getElementById("message");
const payButton = document.getElementById("pay-button");
const startOver = document.getElementById("start-over");

async function continueToPayment() {
  message.textContent = "";

  const items = basketItems();
  if (items.length === 0) {
    message.textContent = "Your basket is empty.";
    return;
  }

  payButton.disabled = true;
  payButton.textContent = "Preparing\u2026";

  const payload = {
    region: selectedRegion(),
    locale: document.getElementById("locale").value,
    name: document.getElementById("name").value.trim() || undefined,
    email: document.getElementById("email").value.trim() || undefined,
    force3ds: document.getElementById("force3ds").checked,
    items: items,
  };

  let session;
  try {
    const res = await fetch("/create-payment-session", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    session = await res.json();
    if (!res.ok || session.error) {
      throw new Error(readError(session) || "Could not start the payment.");
    }
  } catch (err) {
    message.textContent = err.message || "Could not start the payment.";
    resetPayButton();
    return;
  }

  await mountFlow(session);
}

// Turn an API error payload into a short readable string.
function readError(data) {
  if (!data || !data.error) return "";
  const e = data.error;
  if (typeof e === "string") return e;
  if (e.error_type) return e.error_type;
  if (Array.isArray(e.error_codes) && e.error_codes.length) return e.error_codes.join(", ");
  try { return JSON.stringify(e); } catch (_) { return "Payment error"; }
}

async function mountFlow(session) {
  // The CDN script exposes a global factory. Support both the current and the
  // older name so the demo keeps working across SDK versions.
  const factory = window.CheckoutWebComponents || window.loadCheckoutWebComponents;
  if (typeof factory !== "function") {
    message.textContent = "Payment library failed to load \u2014 please refresh.";
    resetPayButton();
    return;
  }

  const locale = document.getElementById("locale").value || "en";

  // Brand colours for Flow. These appearance keys are best-effort: if a future
  // SDK version rejects them, we catch the error and mount a plain Flow so the
  // payment still works. The page chrome carries the brand reliably via CSS.
  const appearance = {
    colorAction: "#323416",
    colorPrimary: "#323416",
    colorBorder: "#8C9E6E",
    colorFormBackground: "#FFFFFD",
  };

  const baseConfig = {
    publicKey: publicKey,
    environment: "sandbox",
    locale: locale,
    paymentSession: session,
    // Inline completion (non-redirect methods like raw card). We forward the
    // payment id so the success page can confirm the final status.
    onPaymentCompleted: (_component, paymentResponse) => {
      const id = paymentResponse && (paymentResponse.id || paymentResponse.paymentId);
      window.location.href =
        "/success" + (id ? "?cko-payment-id=" + encodeURIComponent(id) : "");
    },
    onError: (_component, error) => {
      console.error("Flow error:", error);
      message.textContent = "Payment error \u2014 please try again.";
      resetPayButton();
    },
  };

  let checkout;
  try {
    checkout = await factory({
      ...baseConfig,
      appearance: appearance,
      // (Part 5) show the cardholder-name field inside Flow as well.
      componentOptions: { card: { displayCardholderName: "bottom" } },
    });
  } catch (err) {
    console.warn("Appearance/options rejected; using base Flow config.", err);
    checkout = await factory(baseConfig);
  }

  const flow = checkout.create("flow");
  flow.mount("#flow-container");

  // Flow now owns the pay button; lock the basket and offer a restart.
  lockControls(true);
  payButton.style.display = "none";
  startOver.hidden = false;
}

function lockControls(disabled) {
  ["region", "locale", "name", "email", "force3ds"].forEach((id) => {
    const el = document.getElementById(id);
    if (el) el.disabled = disabled;
  });
  document
    .querySelectorAll("#basket button")
    .forEach((b) => { b.disabled = disabled; });
}

function resetPayButton() {
  payButton.disabled = false;
  payButton.textContent = "Continue to payment";
}

// ---- Wire up ---------------------------------------------------------------
document.getElementById("region").addEventListener("change", renderBasket);
payButton.addEventListener("click", continueToPayment);
renderBasket();
