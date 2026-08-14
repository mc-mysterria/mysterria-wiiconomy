package dev.ua.ikeepcalm.wiic.domain.agora.ledger.model;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** One unclaimed row of the {@code stash_items} table. */
public record StashItem(
        UUID id,
        UUID ownerUuid,
        byte[] itemBytes,
        Material material,
        int amount,
        @Nullable String displayName,
        String source,
        @Nullable String sourceRef,
        long createdAt
) {
    public static final String SOURCE_PURCHASE = "PURCHASE";
    public static final String SOURCE_EXPIRED = "EXPIRED";
    public static final String SOURCE_CANCELLED = "CANCELLED";
    public static final String SOURCE_EVICTION = "EVICTION";
    public static final String SOURCE_RECOVERY = "RECOVERY";
}
