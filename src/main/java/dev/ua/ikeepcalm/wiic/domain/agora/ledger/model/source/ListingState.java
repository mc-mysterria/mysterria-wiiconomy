package dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source;

/**
 * Listing lifecycle. {@code PENDING_PAYMENT} is a short-lived reservation taken by
 * the purchase pipeline between the CAS and the Vault withdraw; the expiry sweeper
 * releases stale ones back to {@code ACTIVE}.
 */
public enum ListingState {
    ACTIVE, PENDING_PAYMENT, SOLD, CANCELLED, EXPIRED
}
