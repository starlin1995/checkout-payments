/*
 * Success page logic.
 *   1. Read the payment id from the URL (Checkout.com appends ?cko-payment-id=...
 *      on redirect; our inline completion handler adds the same param).
 *   2. Ask our server for the live payment status via GET /payment-status.
 *   3. If the payment can be refunded, reveal the refund box (Part 5) and let the
 *      user refund a chosen amount.
 */

const symbol = { EUR: "\u20AC", HKD: "HK$", USD: "$" };
function fmt(minor, currency) {
  const s = symbol[currency] || "";
  return s + (minor / 100).toFixed(2);
}

function paymentIdFromUrl() {
  const q = new URLSearchParams(window.location.search);
  return q.get("cko-payment-id") || q.get("payment_id") || "";
}

const statusEl = document.getElementById("status");
const amountEl = document.getElementById("amount");
const referenceEl = document.getElementById("reference");
const idEl = document.getElementById("payment-id");

const refundBox = document.getElementById("refund-box");
const refundAmount = document.getElementById("refund-amount");
const refundButton = document.getElementById("refund-button");
const refundMessage = document.getElementById("refund-message");

// Remember the payment's currency so refunds are formatted/sent correctly.
let currentPaymentId = "";
let currentCurrency = "";

async function loadStatus() {
  currentPaymentId = paymentIdFromUrl();
  if (!currentPaymentId) {
    statusEl.textContent = "No payment reference found";
    return;
  }
  idEl.textContent = currentPaymentId;

  let data;
  try {
    const res = await fetch(
      "/payment-status?payment_id=" + encodeURIComponent(currentPaymentId)
    );
    data = await res.json();
    if (!res.ok || data.error) throw new Error(data.error || "Lookup failed");
  } catch (err) {
    statusEl.textContent = "Could not load status";
    return;
  }

  statusEl.textContent = data.status || "Unknown";
  referenceEl.textContent = data.reference || "\u2014";

  if (typeof data.amount === "number" && data.currency) {
    currentCurrency = data.currency;
    amountEl.textContent = fmt(data.amount, data.currency);
    // Pre-fill the refund box with the full amount in major units.
    refundAmount.value = (data.amount / 100).toFixed(2);
  }

  // Card payments are auto-captured by default, so a successful payment is
  // normally "Captured" (or "Authorized" before capture settles). Either way we
  // allow a refund attempt; the API is the final authority on whether it works.
  const status = (data.status || "").toLowerCase();
  if (status === "captured" || status === "authorized" || status === "paid") {
    refundBox.hidden = false;
  }
}

async function doRefund() {
  refundMessage.textContent = "";
  const major = parseFloat(refundAmount.value);
  if (isNaN(major) || major <= 0) {
    refundMessage.textContent = "Enter a valid amount.";
    return;
  }
  const minor = Math.round(major * 100); // back to minor units for the API

  refundButton.disabled = true;
  refundButton.textContent = "Refunding\u2026";

  try {
    const res = await fetch("/refund", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ payment_id: currentPaymentId, amount: minor }),
    });
    const data = await res.json();
    if (!res.ok || data.error) throw new Error(readError(data) || "Refund failed");

    const shown = currentCurrency ? fmt(minor, currentCurrency) : major.toFixed(2);
    refundMessage.textContent = "Refund of " + shown + " submitted (action: " +
      (data.action_id || data.id || "ok") + ").";
    refundButton.textContent = "Refunded";
  } catch (err) {
    refundMessage.textContent = err.message || "Refund failed.";
    refundButton.disabled = false;
    refundButton.textContent = "Refund";
  }
}

// Turn an API error payload into a short readable string.
function readError(data) {
  if (!data || !data.error) return "";
  const e = data.error;
  if (typeof e === "string") return e;
  if (e.error_type) return e.error_type;
  if (Array.isArray(e.error_codes) && e.error_codes.length) return e.error_codes.join(", ");
  try { return JSON.stringify(e); } catch (_) { return "Refund error"; }
}

refundButton.addEventListener("click", doRefund);
loadStatus();
