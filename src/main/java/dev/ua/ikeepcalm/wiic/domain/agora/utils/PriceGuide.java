package dev.ua.ikeepcalm.wiic.domain.agora.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.db.ListingDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.coi.CoiFacts;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source.ItemType;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.coi.ItemInspector;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopPricing;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * What the Fence thinks your goods are worth — the number the price screen opens on.
 *
 * <p>A suggestion, never a rule: the seller can move the price anywhere between
 * {@code listings.min-price} and {@code listings.max-price}. It exists so that a
 * player holding a Sequence 4 characteristic doesn't list it for 64 coppets because
 * that was the default, and so the beyonder economy has a shared sense of scale.
 *
 * <p>Two sources, in order of authority:
 * <ol>
 *   <li><b>What the market has been paying.</b> The median of the last few completed
 *       sales of the same kind of goods (per unit). Needs no knowledge of anything —
 *       it is simply true, and it tracks the economy as players move it.</li>
 *   <li><b>A rule-based prior.</b> For beyonder goods, an exponential curve on the
 *       sequence the item serves, scaled by which family of good it is. For ordinary
 *       materials, the admin shop's own price via {@link ShopPricing}.</li>
 * </ol>
 * With enough sales the two are blended ({@code valuation.history.weight}); with none,
 * the prior stands alone.
 *
 * <h2>Why the sequence drives everything</h2>
 * A pathway runs from Sequence 9 (a trinket) to Sequence 0 (a near-deity). Value has to
 * follow that curve or the market says nothing true about the world. Potions, formulae
 * and characteristics carry their sequence in their own PDC. <b>Ingredients do not</b> —
 * they carry only an id — so their sequence comes from {@code coi-ingredients.yml},
 * which records the deepest formula each one feeds. An ingredient is then priced as a
 * fraction of the potion it is a component of, main components being worth several times
 * the filler.
 */
public class PriceGuide {

    /**
     * Where a suggestion came from, so the price screen can say so honestly instead of
     * presenting a guess as a valuation.
     */
    public enum Basis {
        /** Blended with, or taken from, completed sales of the same goods. */
        OBSERVED,
        /** Derived from the item's sequence and family. */
        BEYONDER,
        /** Taken from the admin shop's own price for this material. */
        SHOP,
        /** Nothing to go on; the configured floor. */
        NONE
    }

    /**
     * @param unitPrice suggested price for a single item.
     * @param total     suggested price for the whole stack — what the screen opens on.
     * @param sampled   how many past sales fed the number.
     */
    public record Suggestion(long unitPrice, long total, Basis basis, int sampled, CoiFacts facts) {}

    private final WIIC plugin;
    private final MarketConfig config;
    private final MarketDatabase db;
    private final ItemInspector inspector;

    /** Ingredient ids absent from the index, so each one is only complained about once. */
    private static final Set<String> WARNED_INGREDIENTS = ConcurrentHashMap.newKeySet();

    /** What the price screen opens on when valuation is switched off entirely. */
    private static final long NEUTRAL_OPENING_PRICE = 64;

    public PriceGuide(WIIC plugin, MarketConfig config, MarketDatabase db, ItemInspector inspector) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.inspector = inspector;
    }

    /**
     * Suggests an asking price for {@code item}. The sales lookup is a DB read, so the
     * result arrives on the main thread through {@code callback}.
     */
    public void suggest(ItemStack item, Consumer<Suggestion> callback) {
        CoiFacts facts = inspector.inspect(item);
        int amount = Math.max(1, item.getAmount());

        if (!config.valuationEnabled()) {
            // Switched off entirely: the Fence offers no opinion and the screen opens on a
            // neutral figure, exactly as it did before there was a price guide.
            callback.accept(clampTo(NEUTRAL_OPENING_PRICE, 1, Basis.NONE, 0, facts));
            return;
        }
        long prior = prior(item, facts);

        db.submitThenMain(conn -> ListingDao.recentSoldUnitPrices(conn, facts.valueKey(), config.valuationHistorySize()),
                sales -> callback.accept(blend(prior, sales, amount, facts)),
                error -> {
                    plugin.getLogger().warning("Price history lookup failed for " + facts.valueKey() + ": " + error);
                    callback.accept(clampTo(prior, amount, basisOf(facts), 0, facts));
                });
    }

    // -------------------------------------------------------------------------
    // Observed sales
    // -------------------------------------------------------------------------

    private Suggestion blend(long prior, List<Long> unitSales, int amount, CoiFacts facts) {
        if (unitSales.size() < config.valuationHistoryMinSales()) {
            return clampTo(prior, amount, basisOf(facts), unitSales.size(), facts);
        }
        long observed = median(unitSales);
        // The median already ignores one wild outlier; the weight keeps a thin market
        // from dragging the whole scale around after three odd sales.
        double weight = config.valuationHistoryWeight();
        long blended = Math.round(observed * weight + prior * (1 - weight));
        return clampTo(blended, amount, Basis.OBSERVED, unitSales.size(), facts);
    }

    private static long median(List<Long> sorted) {
        int size = sorted.size();
        if (size == 0) return 0;
        // ListingDao returns them ordered by price, so the middle is the median.
        return size % 2 == 1
                ? sorted.get(size / 2)
                : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }

    // -------------------------------------------------------------------------
    // The prior
    // -------------------------------------------------------------------------

    private long prior(ItemStack item, CoiFacts facts) {
        if (facts.isCoi()) return beyonderPrior(facts);
        // Ordinary goods: whatever the admin shop charges is the honest anchor, and it
        // already moves with the MarketIndex.
        var shop = WIIC.INSTANCE.getShopServices();
        if (shop != null) {
            long unit = shop.pricing().unitPrice(item.getType());
            if (unit > 0) return unit;
        }
        return config.minPrice();
    }

    /**
     * {@code base * ratio^(9 - sequence) * kindMultiplier}.
     *
     * <p>Exponential rather than linear because that is how the source material treats
     * sequences: each step up is a different order of being, not an increment.
     */
    private long beyonderPrior(CoiFacts facts) {
        int sequence = effectiveSequence(facts);
        double base = config.valuationSequenceBase();
        double curve = base * Math.pow(config.valuationSequenceRatio(), 9 - sequence);
        return Math.max(1, Math.round(curve * kindMultiplier(facts)));
    }

    /** The sequence an item is <i>for</i>, falling back to the shallowest when unknown. */
    private int effectiveSequence(CoiFacts facts) {
        if (facts.servedSequence() != null) return Math.clamp(facts.servedSequence(), 0, 9);
        if (facts.kind() == ItemType.INGREDIENT && facts.ingredientKey() != null
                && WARNED_INGREDIENTS.add(facts.ingredientKey())) {
            plugin.getLogger().info("Market price guide has no sequence for CoI ingredient '"
                    + facts.ingredientKey() + "' — add it to coi-ingredients.yml (valued as sequence "
                    + config.valuationUnknownIngredientSequence() + " for now)");
        }
        return config.valuationUnknownIngredientSequence();
    }

    /**
     * How a family of goods relates to the potion of the same sequence.
     *
     * <p>A characteristic outvalues the potion it brews into: it is the half of a formula
     * you cannot mine or farm. Ingredients are fractions of the whole, and an artifact is
     * priced off its own seal level instead of a pathway sequence.
     */
    private double kindMultiplier(CoiFacts facts) {
        return switch (facts.kind()) {
            case POTION -> config.valuationKindMultiplier("potion", 1.0);
            case CHARACTERISTIC -> config.valuationKindMultiplier("characteristic", 2.5);
            case FORMULA -> config.valuationKindMultiplier("formula", 0.4);
            case IMBUED -> config.valuationKindMultiplier("imbued", 1.2);
            case ARTIFACT -> artifactMultiplier(facts.artifactLevel());
            case INGREDIENT -> facts.ingredientKey() != null && config.ingredientIsMain(facts.ingredientKey())
                    ? config.valuationKindMultiplier("ingredient-main", 0.12)
                    : config.valuationKindMultiplier("ingredient-supplementary", 0.05);
            case OTHER, NONE -> config.valuationKindMultiplier("other", 0.5);
        };
    }

    /**
     * Artifacts have no pathway sequence, only a seal level: 0 is a Sealed Artifact, 3 a
     * Normal Item. {@link #effectiveSequence} hands them the fallback sequence, so the
     * multiplier carries the whole spread on its own.
     *
     * <p>The defaults put a Sealed Artifact alongside a Sequence 0 potion — both are the
     * kind of thing an organisation goes to war over — and a Normal Item somewhere around
     * a Sequence 6 potion.
     */
    private double artifactMultiplier(@Nullable Integer level) {
        int resolved = level == null ? 3 : Math.clamp(level, 0, 3);
        return switch (resolved) {
            case 0 -> config.valuationKindMultiplier("artifact-sealed", 1200.0);
            case 1 -> config.valuationKindMultiplier("artifact-mystical", 300.0);
            case 2 -> config.valuationKindMultiplier("artifact-extraordinary", 60.0);
            default -> config.valuationKindMultiplier("artifact-normal", 12.0);
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Basis basisOf(CoiFacts facts) {
        if (facts.isCoi()) return Basis.BEYONDER;
        return Basis.SHOP;
    }

    /** Scales a per-unit suggestion to the stack and keeps it inside the listing bounds. */
    private Suggestion clampTo(long unitPrice, int amount, Basis basis, int sampled, CoiFacts facts) {
        long unit = Math.max(config.minPrice(), unitPrice);
        long total = Math.clamp(unit * (long) amount, config.minPrice(), config.maxPrice());
        return new Suggestion(unit, total, basis, sampled, facts);
    }
}
