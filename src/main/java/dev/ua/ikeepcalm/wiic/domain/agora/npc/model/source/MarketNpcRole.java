package dev.ua.ikeepcalm.wiic.domain.agora.npc.model.source;

import org.jetbrains.annotations.Nullable;

/**
 * What a market NPC does when right-clicked. Stored on the Citizens NPC itself as
 * persistent metadata ({@code wiic-market-role}), so it survives Citizens' own
 * saves with no registry file to drift.
 */
public enum MarketNpcRole {
    /** The Fence — list your own items, manage your listings. */
    BROKER,
    /** Bazaar Clerk — browse and buy player listings. */
    CLERK,
    /** Ledger Keeper — claim sale proceeds and stashed items. */
    BANKER,
    /** Quartermaster — the relocated admin /shop. */
    SHOPKEEPER,
    /** The Informant — search listings by pathway/sequence. */
    INFORMANT,
    /** Plot Warden — rent and manage prestige plots (phase 2). */
    PLOT_WARDEN,
    /** A renter's stall vendor — browse listings attributed to one plot (phase 2). */
    PLOT_VENDOR,
    /** Courier Post — deposit a postman horn for auto-delivery (phase 3). */
    COURIER_POST;

    public static @Nullable MarketNpcRole fromString(@Nullable String value) {
        if (value == null) return null;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
