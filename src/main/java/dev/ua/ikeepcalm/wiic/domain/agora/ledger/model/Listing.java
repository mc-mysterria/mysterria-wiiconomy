package dev.ua.ikeepcalm.wiic.domain.agora.ledger.model;

import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source.ListingState;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One row of the {@code listings} table. Immutable snapshot; state changes go through
 * the DAO.
 *
 * @param valueKey identity for "goods of this exact sort" — what the price guide groups
 *                 completed sales by. Null on rows written before the column existed.
 */
public record Listing(
        UUID id,
        UUID sellerUuid,
        String sellerName,
        byte[] itemBytes,
        Material material,
        int amount,
        @Nullable String displayName,
        String category,
        boolean coiItem,
        @Nullable String coiPathway,
        @Nullable Integer coiSequence,
        long price,
        ListingState state,
        @Nullable UUID buyerUuid,
        @Nullable String plotId,
        long createdAt,
        long expiresAt,
        @Nullable String valueKey
) {}
