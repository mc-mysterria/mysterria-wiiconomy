package dev.ua.ikeepcalm.wiic.domain.agora.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.db.ListingDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.source.ItemType;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.coi.CoiFacts;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.coi.ItemInspector;
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
 *       materials, {@link MaterialValuator} — the admin shop's own price where {@code /shop}
 *       stocks it, and otherwise what the server's recipe graph says it takes to make one.</li>
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
     * Sequence 9 — the shallow end of every pathway, and the anchor the curve hangs off.
     */
    private static final int SHALLOWEST_SEQUENCE = 9;

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
    private final MaterialValuator valuator;

    /** Ingredient ids absent from the index, so each one is only complained about once. */
    private static final Set<String> WARNED_INGREDIENTS = ConcurrentHashMap.newKeySet();

    /** What the price screen opens on when valuation is switched off entirely. */
    private static final long NEUTRAL_OPENING_PRICE = 64;

    public PriceGuide(WIIC plugin, MarketConfig config, MarketDatabase db, ItemInspector inspector) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.inspector = inspector;
        this.valuator = new MaterialValuator(plugin, config);
    }

    /**
     * Drops every derived figure, so a config reload is actually felt.
     */
    public void invalidate() {
        valuator.invalidate();
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
        // Built here, on the main thread: the vanilla half reads the server's recipe
        // registry, which is not safe to touch from the database executor.
        Prior prior = prior(item, facts);

        db.submitThenMain(conn -> ListingDao.recentSoldUnitPrices(conn, facts.valueKey(), config.valuationHistorySize()),
                sales -> callback.accept(blend(prior, sales, amount, facts)),
                error -> {
                    plugin.getLogger().warning("Price history lookup failed for " + facts.valueKey() + ": " + error);
                    callback.accept(clampTo(prior.unitPrice(), amount, prior.basis(), 0, facts));
                });
    }

    private Suggestion blend(Prior prior, List<Long> unitSales, int amount, CoiFacts facts) {
        if (unitSales.size() < config.valuationHistoryMinSales()) {
            return clampTo(prior.unitPrice(), amount, prior.basis(), unitSales.size(), facts);
        }
        long observed = median(unitSales);
        // The median already ignores one wild outlier; the weight keeps a thin market
        // from dragging the whole scale around after three odd sales.
        double weight = config.valuationHistoryWeight();
        long blended = Math.round(observed * weight + prior.unitPrice() * (1 - weight));
        return clampTo(blended, amount, Basis.OBSERVED, unitSales.size(), facts);
    }

    // -------------------------------------------------------------------------
    // Observed sales
    // -------------------------------------------------------------------------

    private Prior prior(ItemStack item, CoiFacts facts) {
        if (facts.isCoi()) return beyonderPrior(facts);
        return vanillaPrior(item);
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

    /**
     * {@code base * ratio^(9 - sequence) * kindMultiplier}.
     *
     * <p>Exponential rather than linear because that is how the source material treats
     * sequences: each step up is a different order of being, not an increment.
     */
    private Prior beyonderPrior(CoiFacts facts) {
        Integer served = facts.servedSequence();
        if (served != null) {
            return new Prior(curveValue(Math.clamp(served, 0, 9), facts), Basis.BEYONDER);
        }
        if (facts.kind() == ItemType.INGREDIENT) {
            return new Prior(curveValue(unknownIngredientSequence(facts), facts), Basis.ESTIMATED);
        }
        // Artifacts, and any other CoI good carrying no sequence at all: their spread lives
        // entirely in kindMultiplier, which is calibrated against the shallowest anchor.
        return new Prior(curveValue(SHALLOWEST_SEQUENCE, facts), Basis.BEYONDER);
    }

    private long curveValue(int sequence, CoiFacts facts) {
        double base = config.valuationSequenceBase();
        double curve = base * Math.pow(config.valuationSequenceRatio(), SHALLOWEST_SEQUENCE - sequence);
        return Math.max(1, Math.round(curve * kindMultiplier(facts)));
    }

    /**
     * The assumed depth of an ingredient the index cannot place, complaining once per id so
     * that the gap shows up in the console rather than only in someone's wallet.
     *
     * <p>This is the path that priced a freshly-added deep-sequence ingredient at thirteen
     * coppets: the old fallback assumed Sequence 9, which is precisely the wrong guess for
     * an id that is missing because it is <em>new</em>. It now assumes the middle of the
     * range and, more importantly, reports itself as an estimate rather than a valuation.
     */
    private int unknownIngredientSequence(CoiFacts facts) {
        if (facts.ingredientKey() != null && WARNED_INGREDIENTS.add(facts.ingredientKey())) {
            plugin.getLogger().warning("Market price guide has no sequence for CoI ingredient '"
                    + facts.ingredientKey() + "' — add it to coi-ingredients.yml. Until then it is"
                    + " offered as a sequence " + config.valuationUnknownIngredientSequence()
                    + " estimate, which is a guess and will be wrong for anything CoI has added"
                    + " since this WIIC build.");
        }
        return config.valuationUnknownIngredientSequence();
    }

    /**
     * Ordinary goods. {@link MaterialValuator} works on the same index-free scale as
     * {@code shop.yml}'s base prices, so the live {@code MarketIndex} is applied here,
     * once, exactly as {@code ShopPricing} would have done — the market's whole sense of
     * scale then moves together whether the number came from the catalogue or from a
     * recipe.
     */
    private Prior vanillaPrior(ItemStack item) {
        MaterialValuator.Valuation valuation = valuator.appraise(item);
        double index = 1.0;
        var shop = WIIC.INSTANCE.getShopServices();
        if (shop != null) index = shop.marketIndex().currentIndex();

        long unit = Math.max(config.minPrice(), Math.round(valuation.unitPrice() * index));
        Basis basis = switch (valuation.source()) {
            case SHOP, RAW -> Basis.SHOP;
            case CRAFTED -> Basis.CRAFTED;
            case GUESS -> Basis.NONE;
        };
        return new Prior(unit, basis);
    }

    /**
     * Artifacts have no pathway sequence, only a seal level: 0 is a Sealed Artifact, 3 a
     * Normal Item. {@link #beyonderPrior} anchors an untagged artifact at
     * {@link #SHALLOWEST_SEQUENCE}, so the multiplier carries the whole spread on its own.
     *
     * <p>The defaults put a Sealed Artifact just above a Sequence 1 characteristic — the
     * deepest thing anyone can currently brew, and the kind of thing an organisation goes
     * to war over — and a Normal Item alongside a Sequence 9 one.
     */
    private double artifactMultiplier(@Nullable Integer level) {
        int resolved = level == null ? 3 : Math.clamp(level, 0, 3);
        return switch (resolved) {
            case 0 -> config.valuationKindMultiplier("artifact-sealed", 220.0);
            case 1 -> config.valuationKindMultiplier("artifact-mystical", 55.0);
            case 2 -> config.valuationKindMultiplier("artifact-extraordinary", 12.0);
            default -> config.valuationKindMultiplier("artifact-normal", 2.5);
        };
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
                    ? config.valuationKindMultiplier("ingredient-main", 0.20)
                    : config.valuationKindMultiplier("ingredient-supplementary", 0.09);
            case OTHER, NONE -> config.valuationKindMultiplier("other", 0.5);
        };
    }

    /**
     * Where a suggestion came from, so the price screen can say so honestly instead of
     * presenting a guess as a valuation.
     */
    public enum Basis {
        /**
         * Blended with, or taken from, completed sales of the same goods.
         */
        OBSERVED,
        /**
         * Derived from the item's sequence and family.
         */
        BEYONDER,
        /**
         * Beyonder goods whose depth the Fence could not establish — an ingredient the
         * index has never heard of. A middling guess, and openly labelled as one.
         */
        ESTIMATED,
        /**
         * Taken from the admin shop's own price for this material.
         */
        SHOP,
        /**
         * Worked out from what it takes to make one — see {@link MaterialValuator}.
         */
        CRAFTED,
        /**
         * Nothing to go on; the configured floor.
         */
        NONE
    }

    /**
     * A per-unit opening figure and an honest account of where it came from.
     */
    private record Prior(long unitPrice, Basis basis) {
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------


    /** Scales a per-unit suggestion to the stack and keeps it inside the listing bounds. */
    private Suggestion clampTo(long unitPrice, int amount, Basis basis, int sampled, CoiFacts facts) {
        long unit = Math.max(config.minPrice(), unitPrice);
        long total = Math.clamp(unit * (long) amount, config.minPrice(), config.maxPrice());
        return new Suggestion(unit, total, basis, sampled, facts);
    }
}
