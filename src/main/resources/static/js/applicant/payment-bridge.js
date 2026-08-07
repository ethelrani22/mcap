document.addEventListener("DOMContentLoaded", function () {
    const payBtn = document.getElementById("payBtn");
    if (!payBtn) return;

    const razorpayOrderId = payBtn.getAttribute("data-razorpay-order-id");
    const keyId           = payBtn.getAttribute("data-key-id");
    const receiptId       = payBtn.getAttribute("data-receipt-id");

    // Guard: abort early if required params are missing
    if (!razorpayOrderId || razorpayOrderId === "null" ||
        !keyId           || keyId === "null") {
        console.error("[payment-bridge] Missing Razorpay order ID or key ID.");
        alert("Payment session could not be initialised. Please try again.");
        return;
    }

    if (typeof window.Razorpay !== "function") {
        console.error("[payment-bridge] Razorpay SDK not loaded.");
        alert("Payment Gateway failed to load. Please check your internet connection and try again.");
        return;
    }

    // ── Resolve a human-readable description from the receipt ID prefix ─────
    // receiptId looks like APP_<id>_<timestamp>, SEAT_<id>_<timestamp>, or SLIDEUP_<id>_<timestamp>
    function resolveDescription(receiptId) {
        if (!receiptId) return "MCAP Payment";
        if (receiptId.startsWith("APP_"))      return "Application / Registration Fee";
        if (receiptId.startsWith("SLIDEUP_"))  return "Slide Up Fee";
        if (receiptId.startsWith("SEAT_"))     return "Seat Acceptance Fee";
        return "MCAP Payment";
    }

    // ── Razorpay checkout options ────────────────────────────────────────────
    var options = {
        key:      keyId,
        order_id: razorpayOrderId,
        name:     "MCAP — Meghalaya Common Admission Portal",
        description: resolveDescription(receiptId),
        theme:    { color: "#0d6efd" },

        // Called by Razorpay after a SUCCESSFUL payment — do NOT redirect here;
        // instead populate the hidden form and POST to the backend for signature verification.
        handler: function (response) {
            document.getElementById("rzp_payment_id").value = response.razorpay_payment_id;
            document.getElementById("rzp_order_id").value   = response.razorpay_order_id;
            document.getElementById("rzp_signature").value  = response.razorpay_signature;
            document.getElementById("rzpCallbackForm").submit();
        },

        // Called when the user closes the Razorpay modal without completing payment
        modal: {
            ondismiss: function () {
                console.info("[payment-bridge] Payment modal dismissed by user.");
                window.location.href = "/applicants/dashboard";
            }
        }
    };
    // ────────────────────────────────────────────────────────────────────────

    function openCheckout() {
        try {
            var rzp = new window.Razorpay(options);

            // Surface Razorpay-level errors (e.g. network errors during payment)
            rzp.on("payment.failed", function (response) {
                console.error("[payment-bridge] Payment failed:", response.error);
                alert("Payment failed: " + (response.error.description || "Unknown error") +
                      ". Please try again or contact support.");
                window.location.href = "/applicants/dashboard";
            });

            rzp.open();
        } catch (e) {
            console.error("[payment-bridge] Error opening Razorpay checkout:", e);
            alert("Could not open the payment window. Please try again.");
        }
    }

    // Wire fallback button
    payBtn.addEventListener("click", openCheckout);

    // Auto-open after a short delay, then show the fallback button
    setTimeout(function () {
        openCheckout();
        payBtn.classList.remove("d-none"); // Fallback visible if modal fails to appear
    }, 500);
});