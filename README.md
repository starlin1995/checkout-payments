# iPhone Case Store — Checkout.com Flow demo

A small checkout demo for a merchant selling iPhone cases in **Hong Kong (HKD)**
and the **Netherlands (EUR)**. It captures card details securely while keeping the
merchant in the lightest PCI scope, and offers one‑touch wallets (Apple Pay) on
supported devices.

It is a converted version of an existing **Tony's PSP integration** sample.

---

## How it works (integration method)

This demo uses **Checkout.com Flow**.

* The card form is rendered by Flow inside Checkout.com's own iframe, so **raw
  card data never reaches this server**. That keeps the merchant in **PCI SAQ A**
  scope while still showing the payment form on our own page — exactly what the
  brief asks for ("capture card details securely while maintaining PCI
  compliance").
* Flow also renders **wallet buttons such as Apple Pay** automatically on
  supported devices/browsers when they are enabled on the account — the
  "one‑touch payment" the brief mentions, given most shoppers are on iPhone.

End‑to‑end flow:

1. The browser loads the storefront (`GET /`); the server injects the **public**
   key and the product catalog.
2. On *Continue to payment* the browser posts the chosen region + basket to
   `POST /create-payment-session`.
3. The server **prices the basket itself** (it only trusts the product id +
   quantity from the browser — never a price) and calls Checkout.com to create a
   **payment session**, returning it to the browser.
4. Flow mounts with that session, collects the card details and submits the
   payment.
5. The shopper lands on `GET /success` (or `/failure`); the success page confirms
   the final status via `GET /payment-status`.

---

## Running it

Requirements: **JDK 11+** (the project targets Java 11; it builds and runs on
newer JDKs too). No database, no other services.

```bash
# from the project root (so config.properties and the templates resolve):
./gradlew run
```

Then open <http://localhost:8081>.

The shared **sandbox** keys from the case study are already filled in
`config.properties`. To use your own sandbox account, copy
`config.properties.example` and replace the values.

### Test card

| Field   | Value                  |
|---------|------------------------|
| Number  | `4242 4242 4242 4242`  |
| Expiry  | any **future** date    |
| CVV     | `100`                  |

Pick a region (currency), set quantities, click **Continue to payment**, then pay
with the test card. The success page shows the live status and lets you issue a
refund.

---

## Part 4 — requirements coverage

| Requirement | How it's met |
|---|---|
| Capture card details **securely**, keep **PCI compliance** | Checkout.com **Flow** — card fields live in Checkout.com's iframe; the server never sees card data (SAQ A). |
| Faster checkout / **one‑touch wallet** for iPhone users | Flow surfaces **Apple Pay** automatically on supported Safari/iOS when enabled on the account. |
| Selling in **Hong Kong & the Netherlands** | Region selector maps to **HKD / HK** and **EUR / NL** (USD/US is included to demonstrate other currencies). |
| **Hard‑coded products / basket**, no database | Three cases defined in `Catalog.java`; quantities chosen in the UI; nothing persisted. |
| **Functional** payments in the test environment | Real sandbox payment sessions + the test card above complete a payment. |
| **Brand colours** `#323416 / #8C9E6E / #FFFFFD` | Applied across the page (`checkout.css`), the logo (`img/logo.svg`) and passed to Flow's `appearance`. |

### Notes on the sample interview questions

* **Which integration method & why?** Flow — it shows the card form on our page
  but keeps card data in Checkout.com's iframe, giving secure capture + SAQ A and
  built‑in wallet support in one component.
* **How is the amount kept safe?** The browser only sends product ids and
  quantities. `Catalog.priceBasket(...)` re‑prices on the server (amounts stored
  in **minor units**) before creating the session, so a tampered front‑end can't
  change the charge.

---

## Part 5 — optional features implemented

All of these are included:

* **Collect cardholder name / email and forward to the payment API** — the
  optional name/email fields are sent in the `customer` block of the payment
  session (`CheckoutService.createPaymentSession`). The name is also shown inside
  Flow via `componentOptions.card.displayCardholderName`.
* **Display Flow in another language** — a language selector (English / Dutch /
  French) sets Flow's `locale`.
* **Initiate the transaction with 3DS required** — a *Require 3DS* checkbox adds
  `"3ds": { "enabled": true }` to the payment session.
* **Process other transaction currencies** — the region selector switches between
  EUR, HKD and USD.
* **Register a webhook for `payment_approved` / `payment_captured` /
  `payment_declined`** — `POST /webhooks` verifies the `cko-signature` HMAC
  (when a signing key is configured) and logs these events. Point a dashboard
  webhook at `{APP_BASE_URL}/webhooks` and set `CHECKOUT_WEBHOOK_KEY`.
* **Refund a captured payment by an entered amount** — the success page has a
  refund box (pre‑filled with the full amount); `POST /refund` calls the refunds
  API (amount in minor units; blank/zero = full refund).

---

## Project layout

```
src/main/java/checkout/
  Application.java      Spark routes (storefront, session, status, refund, webhook)
  CheckoutService.java  thin Checkout.com REST client (java.net.http.HttpClient)
  Catalog.java          hard-coded products + server-side basket pricing
src/main/java/view/
  RenderUtil.java       Jinjava render helper            
  CustomResourceLocator.java  template loader            
src/main/resources/
  templates/  checkout.html, success.html, failure.html, error.html
  static/css/ checkout.css
  static/js/  flow.js (Flow integration), success.js (status + refund)
  static/img/ logo.svg, success.svg, failed.svg
config.properties        keys + hosts (sandbox values pre-filled)
config.properties.example
```

### About the HTTP style and dependencies

The original made its calls over plain HTTP. We keep that style using the JDK's
built‑in `java.net.http.HttpClient` (Java 11+).

If you would rather use the official **Checkout.com Java SDK**, a commented line
is left in `build.gradle` (`com.checkout:checkout-sdk-java`). The JSON payloads in
`CheckoutService` map one‑to‑one onto the SDK objects.

### Apple Pay in production

Apple Pay works in this sandbox demo on supported devices when enabled on the
account. You need: serving over **HTTPS**, **domain registration/verification** 
with Apple/Checkout.com, and Apple Pay enabled for the processing channel. 
