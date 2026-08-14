package dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source;

/**
 * The families of CircleOfImagination goods the market can tell apart, purely from
 * PDC tags and the item's material — WIIC never touches a CoI class.
 *
 * <p>How each one is recognised (CoI namespace {@code circleofimagination}):
 * <ul>
 *   <li>{@link #POTION} — {@code sequencePotion} tag, or a POTION carrying
 *       {@code pathway} + {@code sequence}. The advancement itself.</li>
 *   <li>{@link #CHARACTERISTIC} — a PLAYER_HEAD carrying {@code pathway} +
 *       {@code sequence}. Taken off a Beyonder's corpse; the scarce half of a
 *       formula and the reason anyone hunts one.</li>
 *   <li>{@link #FORMULA} — a book carrying {@code pathway} + {@code sequence}.
 *       Knowledge of a recipe, not the ingredients for it.</li>
 *   <li>{@link #ARTIFACT} — {@code artifact} boolean, with {@code id} shaped
 *       {@code "<level>-<name>"}; level 0 is a Sealed Artifact, 3 a Normal Item.</li>
 *   <li>{@link #INGREDIENT} — {@code ingredient} string key, no sequence of its own;
 *       the sequence it serves comes from {@code coi-ingredients.yml}.</li>
 *   <li>{@link #IMBUED} — {@code imbued_pathway} + {@code imbued_sequence} on armour.</li>
 * </ul>
 */
public enum ItemType {
    POTION,
    CHARACTERISTIC,
    FORMULA,
    ARTIFACT,
    INGREDIENT,
    IMBUED,
    /**
     * Tagged by CoI but not a family the valuation knows how to price precisely.
     */
    OTHER,
    /**
     * Not a CircleOfImagination item at all.
     */
    NONE;

    public boolean isCoi() {
        return this != NONE;
    }
}
