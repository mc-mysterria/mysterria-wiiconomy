package dev.ua.ikeepcalm.wiic.domain.agora.ledger.model;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * Denormalized listing columns captured at listing time so browse pages render
 * summary icons without deserializing the item blob. {@code coiPathway}/{@code
 * coiSequence} come from the CircleOfImagination PDC tags (read raw, no classpath
 * dependency); null when the item isn't a beyonder item or CoI isn't installed.
 *
 * @param valueKey identity for "goods of this exact sort" — the column the price
 *                 guide groups completed sales by, so the market can quote what
 *                 things have actually been fetching rather than a guess.
 */
public record ItemSnapshot(
        Material material,
        int amount,
        @Nullable String displayName,
        String category,
        boolean coiItem,
        @Nullable String coiPathway,
        @Nullable Integer coiSequence,
        String valueKey
) {}
