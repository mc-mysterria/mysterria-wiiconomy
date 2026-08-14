package dev.ua.ikeepcalm.wiic.domain.agora.utils.coi;

import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source.ItemType;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the market can learn about a CircleOfImagination item by reading its
 * PDC — the single result of one inspection, shared by the listing snapshot and the
 * price guide so an item is never read twice.
 *
 * @param kind              which family of beyonder good this is.
 * @param pathway           declared pathway, or null (ingredients have none).
 * @param declaredSequence  the {@code sequence} tag exactly as CoI wrote it, or null.
 *                          This is what lands in the {@code listings} table and what
 *                          the Informant searches on — never a value WIIC inferred.
 * @param servedSequence    the sequence this item is <i>for</i>: the declared one when
 *                          there is one, otherwise the deepest sequence whose formula
 *                          consumes it (ingredients). Null when nothing is known.
 * @param ingredientKey     CoI's ingredient id, for INGREDIENT items.
 * @param artifactLevel     0 (Sealed Artifact) to 3 (Normal Item), for ARTIFACT items.
 * @param valueKey          stable identity for "goods of this exact sort", used to
 *                          look up what the market has actually been paying.
 */
public record CoiFacts(
        ItemType kind,
        @Nullable String pathway,
        @Nullable Integer declaredSequence,
        @Nullable Integer servedSequence,
        @Nullable String ingredientKey,
        @Nullable Integer artifactLevel,
        String valueKey
) {

    public static CoiFacts none(String valueKey) {
        return new CoiFacts(ItemType.NONE, null, null, null, null, null, valueKey);
    }

    public boolean isCoi() {
        return kind.isCoi();
    }
}
