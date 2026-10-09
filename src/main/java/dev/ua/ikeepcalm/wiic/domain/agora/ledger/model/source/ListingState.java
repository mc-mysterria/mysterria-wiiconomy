package dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source;

/**
 * Listing lifecycle. {@code PENDING_PAYMENT} is a short-lived reservation taken by
 * the purchase pipeline between the CAS and the Vault withdraw; the expiry sweeper
 * releases stale ones back to {@code ACTIVE}.
 *
 * <p>{@code PAYMENT_HELD} is a reservation whose payment or refund outcome is unknown
 * (the economy provider threw, or a crash landed around the money movement), or whose
 * refund the provider refused, leaving the buyer owed. It keeps
 * the buyer and price on the row and never expires: no purchase, cancel, expiry or
 * recovery path moves it, so the goods stay put until staff settle the attempt by hand.
 */
public enum ListingState {
    ACTIVE, PENDING_PAYMENT, SOLD, CANCELLED, EXPIRED, PAYMENT_HELD
}
