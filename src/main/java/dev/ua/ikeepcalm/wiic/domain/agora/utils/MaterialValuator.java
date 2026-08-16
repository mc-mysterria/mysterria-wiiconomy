package dev.ua.ikeepcalm.wiic.domain.agora.utils;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.shop.model.ShopEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * What an <em>ordinary</em> item is worth, when the goods carry no beyonder tags for
 * {@link PriceGuide} to read.
 *
 * <p>The old answer was "whatever {@code /shop} charges, else the configured floor",
 * and the floor is where almost everything landed: the shop catalogue only stocks
 * plain building <em>blocks</em>, so a beacon, a netherite axe, a diamond and a stack
 * of rotten flesh were all worth exactly one coppet. That is not an estimate, it is an
 * absence of one, and it made the Fence look like a fool.
 *
 * <h2>Where a number comes from</h2>
 * Four sources, first match wins, so that a deliberate statement always beats a derived
 * one:
 * <ol>
 *   <li><b>{@code valuation.vanilla.overrides}</b> — an admin naming a price outright.</li>
 *   <li><b>Seeds.</b> The things nothing crafts: ore, mob drops, crops, treasure. These
 *       have to be asserted because no recipe can explain where they come from. Built-in
 *       defaults live in {@link #SEEDS}; {@code valuation.vanilla.seeds} overrides and
 *       extends them.</li>
 *   <li><b>The shop catalogue's base price</b>, for materials {@code /shop} actually
 *       stocks — the admin already priced those, and the market should agree with them.</li>
 *   <li><b>The server's own recipe graph.</b> Anything craftable, smeltable, cuttable or
 *       forgeable is worth what it takes to make it, recursively, taking the cheapest
 *       route when there are several. This is what separates a beacon (a nether star and
 *       change) from cobblestone without anyone having to write either number down, and
 *       it picks up recipes added by other plugins for free.</li>
 * </ol>
 * Anything still unresolved falls to a shape heuristic — an unstackable item is a tool or
 * a piece of armour and is worth more than a stack of filler — and finally to
 * {@code valuation.vanilla.default-price}.
 *
 * <p>All figures here are <b>index-free</b>: they sit on the same scale as
 * {@link ShopEntry#basePrice()}, and the caller multiplies by the live
 * {@code MarketIndex} once, at the end.
 *
 * <h2>Threading</h2>
 * The recipe index is read from {@link Bukkit#recipeIterator()} on first use and must
 * therefore be built on the main thread. Every caller reaches this through
 * {@link PriceGuide#suggest}, which runs on the main thread before its database hop.
 */
public class MaterialValuator {

    /**
     * Which of the four sources the number came from, so the Fence can say so honestly.
     */
    public enum Source {
        /**
         * An admin's own figure, or a seeded raw material.
         */
        RAW,
        /**
         * The {@code /shop} catalogue's base price.
         */
        SHOP,
        /**
         * Derived from what it takes to make one.
         */
        CRAFTED,
        /**
         * Nothing to go on; a shape heuristic or the configured default.
         */
        GUESS
    }

    /**
     * @param unitPrice index-free coppets for a single item.
     */
    public record Valuation(long unitPrice, Source source) {
    }

    /**
     * How deep the recipe walk goes before giving up. Six is past every vanilla chain
     * that matters (ore → ingot → block → beacon is four) and keeps a plugin's circular
     * recipe from spending real time before the cycle guard notices it.
     */
    private static final int MAX_RECIPE_DEPTH = 6;

    /**
     * Sentinel for "this branch cannot be priced" — never cached, since it is path-dependent.
     */
    private static final long UNKNOWN = -1L;

    /**
     * Hard stop on one top-level walk. Memoisation alone already keeps the real work
     * linear in the number of materials involved; this exists so that a pathological
     * recipe set added by some other plugin cannot cost a tick, whatever it looks like.
     */
    private static final int STEP_BUDGET = 5_000;

    private final WIIC plugin;
    private final MarketConfig config;

    /**
     * Result cache. Dropped wholesale on reload; see {@link #invalidate()}.
     */
    private final Map<Material, Valuation> cache = new EnumMap<>(Material.class);

    /**
     * Memoised intermediate figures from the recipe walk.
     *
     * <p>Without this the walk is exponential: "any plank" is eleven materials, a recipe
     * has up to nine ingredients, and six levels of that is not a number of steps anyone
     * wants inside a click handler.
     *
     * <p>Only real figures are remembered, never {@link #UNKNOWN} — a branch that failed
     * did so because of the path it was on (a cycle, the depth limit), and the same
     * material reached another way may well resolve. The converse costs a little accuracy:
     * a figure first computed on a path where the cycle guard hid a cheaper route is kept
     * even if that route later becomes visible. An occasional slightly-high estimate on a
     * circular recipe chain is a fair price for a bounded walk.
     */
    private final Map<Material, Long> partials = new EnumMap<>(Material.class);

    /**
     * Steps spent in the current top-level walk; see {@link #STEP_BUDGET}.
     */
    private int steps;

    /**
     * Every recipe on the server, indexed by what it produces. Built once, lazily.
     */
    private @Nullable Map<Material, List<Recipe>> recipesByResult;

    /**
     * {@code valuation.vanilla.seeds}, parsed once per reload.
     */
    private @Nullable Map<Material, Long> configuredSeeds;

    public MaterialValuator(WIIC plugin, MarketConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Drops every cached figure, so a {@code /wiic reload} is actually felt.
     */
    public synchronized void invalidate() {
        cache.clear();
        partials.clear();
        recipesByResult = null;
        configuredSeeds = null;
    }

    // -------------------------------------------------------------------------
    // Entry points
    // -------------------------------------------------------------------------

    /**
     * What one of {@code item} is worth, condition and enchantments included.
     *
     * <p>The material sets the scale; the item's own state adjusts it. A worn-out pickaxe
     * is worth less than a fresh one, and an enchanted anything is worth a great deal more
     * than the sum of its materials — which is the whole reason players want it.
     */
    public synchronized Valuation appraise(ItemStack item) {
        Valuation base = value(item.getType());
        if (!item.hasItemMeta()) return base;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return base;

        long unit = base.unitPrice();

        // Damage first: a half-broken sword's *enchantments* are still worth full price,
        // since anyone can grind them off onto something new.
        short maxDurability = item.getType().getMaxDurability();
        if (maxDurability > 0 && meta instanceof Damageable damageable && damageable.hasDamage()) {
            double remaining = 1.0 - (double) damageable.getDamage() / maxDurability;
            unit = Math.max(1, Math.round(unit * Math.clamp(remaining, 0.2, 1.0)));
        }

        long perLevel = config.valuationEnchantPerLevel();
        if (perLevel > 0) {
            int levels = 0;
            for (int level : item.getEnchantments().values()) levels += level;
            if (meta instanceof EnchantmentStorageMeta storage) {
                for (int level : storage.getStoredEnchants().values()) levels += level;
            }
            unit += (long) levels * perLevel;
        }
        return unit == base.unitPrice() ? base : new Valuation(unit, base.source());
    }

    /**
     * The index-free worth of one {@code material}, cached.
     */
    public synchronized Valuation value(Material material) {
        Valuation cached = cache.get(material);
        if (cached != null) return cached;
        steps = 0;
        Valuation resolved = resolve(material, EnumSet.noneOf(Material.class), 0);
        cache.put(material, resolved);
        return resolved;
    }

    // -------------------------------------------------------------------------
    // Resolution
    // -------------------------------------------------------------------------

    private Valuation resolve(Material material, Set<Material> resolving, int depth) {
        Long override = seed(material);
        if (override != null) return new Valuation(Math.max(1, override), Source.RAW);

        long shop = shopBasePrice(material);
        if (shop > 0) return new Valuation(shop, Source.SHOP);

        long crafted = craftedValue(material, resolving, depth);
        if (crafted != UNKNOWN) return new Valuation(Math.max(1, crafted), Source.CRAFTED);

        return new Valuation(Math.max(1, heuristic(material)), Source.GUESS);
    }

    /**
     * One component of a recipe.
     *
     * <p>Unlike {@link #resolve} this can answer {@link #UNKNOWN}, and the distinction is
     * the whole reason the two are separate. A component that cannot be priced <em>because
     * of where we are</em> — a cycle back onto something already being resolved, or the
     * depth limit — has to discard the recipe, because guessing there is how an iron ingot
     * ends up worth nine guesses at an iron nugget. A component that nothing anywhere
     * explains is a different case: it is simply a raw material nobody seeded, and the
     * shape heuristic answers it rather than throwing away a recipe that is otherwise fine.
     */
    private long ingredientValue(Material material, Set<Material> resolving, int depth) {
        Valuation cached = cache.get(material);
        if (cached != null) return cached.unitPrice();
        Long remembered = partials.get(material);
        if (remembered != null) return remembered;

        Long seeded = seed(material);
        if (seeded != null) return remember(material, Math.max(1, seeded));

        long shop = shopBasePrice(material);
        if (shop > 0) return remember(material, shop);

        if (resolving.contains(material)) return UNKNOWN;

        long crafted = craftedValue(material, resolving, depth);
        if (crafted != UNKNOWN) return remember(material, Math.max(1, crafted));

        return hasRecipes(material) ? UNKNOWN : remember(material, heuristic(material));
    }

    private long remember(Material material, long value) {
        partials.put(material, value);
        return value;
    }

    /**
     * Cheapest way the server knows to produce one {@code material}, or {@link #UNKNOWN}.
     */
    private long craftedValue(Material material, Set<Material> resolving, int depth) {
        if (!config.valuationVanillaCraftingEnabled()) return UNKNOWN;
        if (depth >= maxDepth()) return UNKNOWN;
        if (++steps > STEP_BUDGET) return UNKNOWN;

        List<Recipe> recipes = recipeIndex().get(material);
        if (recipes == null || recipes.isEmpty()) return UNKNOWN;

        // LinkedHashSet, not a plain add/remove on a shared set: the guard has to describe
        // the path currently being walked, so that two sibling branches through the same
        // material don't see each other as a cycle.
        Set<Material> nested = new LinkedHashSet<>(resolving);
        nested.add(material);

        long cheapest = UNKNOWN;
        for (Recipe recipe : recipes) {
            long cost = recipeCost(recipe, nested, depth + 1);
            if (cost == UNKNOWN) continue;
            if (cheapest == UNKNOWN || cost < cheapest) cheapest = cost;
        }
        if (cheapest == UNKNOWN) return UNKNOWN;
        return Math.max(1, Math.round(cheapest * config.valuationCraftMarkup()));
    }

    /**
     * Sum of a recipe's inputs, divided by how many items it yields.
     */
    private long recipeCost(Recipe recipe, Set<Material> resolving, int depth) {
        List<RecipeChoice> inputs = inputsOf(recipe);
        if (inputs == null || inputs.isEmpty()) return UNKNOWN;

        long total = 0;
        for (RecipeChoice choice : inputs) {
            long value = choiceValue(choice, resolving, depth);
            if (value == UNKNOWN) return UNKNOWN;
            total += value;
        }
        int yield;
        try {
            yield = Math.max(1, recipe.getResult().getAmount());
        } catch (Throwable t) {
            return UNKNOWN;
        }
        return Math.max(1, total / yield);
    }

    /**
     * The ingredients of the recipe kinds worth reasoning about. Brewing and smithing
     * <em>trims</em> are deliberately absent: brewing is not exposed as a {@link Recipe}
     * at all, and a trim's result is a placeholder that would price every trimmed armour
     * piece as the template alone.
     */
    private static @Nullable List<RecipeChoice> inputsOf(Recipe recipe) {
        List<RecipeChoice> inputs = new ArrayList<>();
        switch (recipe) {
            case ShapedRecipe shaped -> {
                for (RecipeChoice choice : shaped.getChoiceMap().values()) {
                    if (choice != null) inputs.add(choice);
                }
            }
            case ShapelessRecipe shapeless -> inputs.addAll(shapeless.getChoiceList());
            case CookingRecipe<?> cooking -> inputs.add(cooking.getInputChoice());
            case StonecuttingRecipe cutting -> inputs.add(cutting.getInputChoice());
            case SmithingTrimRecipe ignored -> {
                return null;
            }
            case SmithingRecipe smithing -> {
                if (smithing instanceof SmithingTransformRecipe transform && transform.getTemplate() != null) {
                    inputs.add(transform.getTemplate());
                }
                if (smithing.getBase() != null) inputs.add(smithing.getBase());
                if (smithing.getAddition() != null) inputs.add(smithing.getAddition());
            }
            default -> {
                return null;
            }
        }
        return inputs;
    }

    /**
     * A {@link RecipeChoice} is a set of acceptable materials (any log, any plank), so it
     * is worth the cheapest one — that is what a player would actually feed it.
     */
    private long choiceValue(RecipeChoice choice, Set<Material> resolving, int depth) {
        List<Material> options = switch (choice) {
            case RecipeChoice.MaterialChoice materials -> materials.getChoices();
            case RecipeChoice.ExactChoice exact -> exact.getChoices().stream().map(ItemStack::getType).toList();
            default -> List.of();
        };
        if (options.isEmpty()) return UNKNOWN;

        long cheapest = UNKNOWN;
        for (Material option : options) {
            long value = ingredientValue(option, resolving, depth);
            if (value == UNKNOWN) continue;
            if (cheapest == UNKNOWN || value < cheapest) cheapest = value;
        }
        return cheapest;
    }

    private int maxDepth() {
        return Math.clamp(config.valuationMaxRecipeDepth(), 1, MAX_RECIPE_DEPTH);
    }

    /**
     * Whether anything on this server makes {@code material} — see {@link #ingredientValue}.
     */
    private boolean hasRecipes(Material material) {
        if (!config.valuationVanillaCraftingEnabled()) return false;
        List<Recipe> recipes = recipeIndex().get(material);
        return recipes != null && !recipes.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Sources
    // -------------------------------------------------------------------------

    /**
     * {@code valuation.vanilla.overrides}, then {@code seeds}, then the built-in table.
     *
     * <p>{@code valuation.vanilla.scale} multiplies the built-in figures and nothing else.
     * The table below is a statement about what things are worth <em>relative to each
     * other</em>, which is the part the recipe walk needs and the part that is tedious to
     * get right; how expensive the game is overall is one number, and an admin who wants
     * a richer or poorer server should not have to edit a hundred and fifty seeds to say
     * so. A figure an admin has written by hand is already absolute and is left alone.
     */
    private @Nullable Long seed(Material material) {
        long override = config.valuationVanillaOverride(material.name());
        if (override > 0) return override;

        Long configured = configuredSeeds().get(material);
        if (configured != null) return configured;

        Long builtin = SEEDS.get(material);
        if (builtin == null) builtin = oreSeed(material);
        if (builtin == null) builtin = familySeed(material);
        if (builtin == null) return null;

        double scale = config.valuationVanillaScale();
        return scale == 1.0 ? builtin : Math.max(1, Math.round(builtin * scale));
    }

    /**
     * Whole families that are loot rather than craft, matched by name so a new member is
     * covered the day it ships.
     *
     * <p>Smithing templates are the case that forced this. They are structure loot, but
     * they also have a duplication recipe that consumes one of themselves — so a recipe
     * walk hits the cycle guard, gives up, and takes every netherite item down with it: a
     * netherite sword was valued at the shape heuristic's 140 rather than the four thousand
     * its ingot is worth. Asserting the template ends that.
     */
    private static @Nullable Long familySeed(Material material) {
        String name = material.name();
        if (name.endsWith("_SMITHING_TEMPLATE")) return 60L;
        if (name.endsWith("_POTTERY_SHERD")) return 20L;
        if (name.endsWith("_BANNER_PATTERN")) return 25L;
        if (name.endsWith("_SPAWN_EGG")) return 90L;
        if (name.startsWith("MUSIC_DISC_")) return 40L;
        return null;
    }

    private Map<Material, Long> configuredSeeds() {
        Map<Material, Long> loaded = configuredSeeds;
        if (loaded != null) return loaded;

        loaded = new EnumMap<>(Material.class);
        ConfigurationSection section = config.valuationVanillaSeeds();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Material material = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
                if (material == null) {
                    plugin.getLogger().warning("valuation.vanilla.seeds: '" + key + "' is not a material");
                    continue;
                }
                long value = section.getLong(key, 0);
                if (value > 0) loaded.put(material, value);
            }
        }
        configuredSeeds = loaded;
        return loaded;
    }

    /**
     * {@code /shop}'s own base price, before the market index.
     */
    private static long shopBasePrice(Material material) {
        var shop = WIIC.INSTANCE.getShopServices();
        if (shop == null) return 0;
        ShopEntry entry = shop.catalog().get(material);
        return entry == null ? 0 : Math.max(0, entry.basePrice());
    }

    /**
     * Ores are asserted per tag rather than per material so that every stone/deepslate
     * variant is covered without listing it, and so a new ore variant is priced the day
     * it ships instead of falling to the default.
     *
     * <p>These are the one place where the market can be checked against a figure the
     * server already states out loud: {@code config.yml}'s {@code ores} section is what
     * the appraiser pays for a silk-touched ore block — diamond 2, emerald 3, ancient
     * debris 5, everything else nothing. That is a deliberately harsh sink (it is also
     * capped at {@code daily-limit} coppets a day), so the market sits about four times
     * above it: enough that selling to another player beats selling to the server, which
     * is the whole point of having a market, without inventing a second economy.
     */
    private static @Nullable Long oreSeed(Material material) {
        if (Tag.COAL_ORES.isTagged(material)) return 1L;
        if (Tag.COPPER_ORES.isTagged(material)) return 2L;
        if (Tag.IRON_ORES.isTagged(material)) return 3L;
        if (Tag.GOLD_ORES.isTagged(material)) return 5L;
        if (Tag.LAPIS_ORES.isTagged(material)) return 2L;
        if (Tag.REDSTONE_ORES.isTagged(material)) return 2L;
        if (Tag.DIAMOND_ORES.isTagged(material)) return 8L;
        if (Tag.EMERALD_ORES.isTagged(material)) return 12L;
        return null;
    }

    /**
     * Last resort: the item's <em>shape</em>. Minecraft encodes scarcity in stack size —
     * a thing you can only carry one of is a tool, a piece of armour or a treasure, and a
     * thing that stacks to 64 is filler. It is a crude signal, but it is a real one, and
     * it beats handing everything the same floor price.
     */
    private long heuristic(Material material) {
        int stackSize = material.getMaxStackSize();
        if (stackSize <= 1) return 14;
        if (stackSize <= 16) return 4;
        return Math.max(1, config.valuationVanillaDefault());
    }

    // -------------------------------------------------------------------------
    // Recipe index
    // -------------------------------------------------------------------------

    private Map<Material, List<Recipe>> recipeIndex() {
        Map<Material, List<Recipe>> index = recipesByResult;
        if (index != null) return index;

        index = new EnumMap<>(Material.class);
        int counted = 0;
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            Material result;
            try {
                // ComplexRecipe (map cloning, firework assembly, ...) has no meaningful
                // fixed result and can throw rather than answer.
                result = recipe.getResult().getType();
            } catch (Throwable t) {
                continue;
            }
            if (result.isAir()) continue;
            index.computeIfAbsent(result, r -> new ArrayList<>()).add(recipe);
            counted++;
        }
        plugin.getLogger().info("Price guide indexed " + counted + " recipes across "
                + index.size() + " materials");
        recipesByResult = index;
        return index;
    }

    // -------------------------------------------------------------------------
    // Built-in seeds
    // -------------------------------------------------------------------------

    /**
     * The things no recipe explains: what you mine, kill, farm or loot for. Everything
     * else in the game is built out of these, so these are the only numbers that have to
     * be asserted — get the scale of this table right and the recipe walk does the rest.
     *
     * <p>Calibrated against {@code shop.yml}'s own base prices (stone 2, wood 3, the
     * catalogue default 4) so that both halves of the economy read on one scale, and
     * against effort: a diamond is a mining trip, a nether star is a raid.
     */
    private static final Map<Material, Long> SEEDS = buildSeeds();

    private static Map<Material, Long> buildSeeds() {
        Map<Material, Long> seeds = new EnumMap<>(Material.class);
        Map<String, Long> byName = new HashMap<>();

        // -- Stone the shop refuses to stock ------------------------------------
        byName.put("OBSIDIAN", 4L);
        byName.put("CRYING_OBSIDIAN", 14L);
        byName.put("ANCIENT_DEBRIS", 20L);
        byName.put("NETHERITE_SCRAP", 20L);
        byName.put("AMETHYST_SHARD", 2L);
        byName.put("SPONGE", 30L);
        byName.put("WET_SPONGE", 28L);
        byName.put("BUDDING_AMETHYST", 80L);
        byName.put("REINFORCED_DEEPSLATE", 300L);

        // -- Minerals -----------------------------------------------------------
        byName.put("COAL", 1L);
        byName.put("CHARCOAL", 1L);
        byName.put("RAW_COPPER", 1L);
        byName.put("COPPER_INGOT", 1L);
        byName.put("RAW_IRON", 2L);
        byName.put("IRON_INGOT", 2L);
        byName.put("IRON_NUGGET", 1L);
        byName.put("RAW_GOLD", 3L);
        byName.put("GOLD_INGOT", 4L);
        byName.put("GOLD_NUGGET", 1L);
        byName.put("REDSTONE", 1L);
        byName.put("LAPIS_LAZULI", 1L);
        byName.put("QUARTZ", 1L);
        byName.put("DIAMOND", 10L);
        byName.put("EMERALD", 6L);
        byName.put("NETHER_QUARTZ_ORE", 2L);
        byName.put("NETHER_GOLD_ORE", 3L);
        byName.put("GILDED_BLACKSTONE", 8L);

        // -- Mob drops ----------------------------------------------------------
        byName.put("ROTTEN_FLESH", 1L);
        byName.put("BONE", 1L);
        byName.put("STRING", 1L);
        byName.put("SPIDER_EYE", 1L);
        byName.put("GUNPOWDER", 1L);
        byName.put("SLIME_BALL", 1L);
        byName.put("LEATHER", 1L);
        byName.put("FEATHER", 1L);
        byName.put("INK_SAC", 1L);
        byName.put("GLOW_INK_SAC", 2L);
        byName.put("RABBIT_HIDE", 1L);
        byName.put("RABBIT_FOOT", 6L);
        byName.put("ENDER_PEARL", 3L);
        byName.put("BLAZE_ROD", 4L);
        byName.put("GHAST_TEAR", 8L);
        byName.put("PHANTOM_MEMBRANE", 3L);
        byName.put("PRISMARINE_SHARD", 1L);
        byName.put("PRISMARINE_CRYSTALS", 2L);
        byName.put("NAUTILUS_SHELL", 12L);
        byName.put("SHULKER_SHELL", 30L);
        byName.put("TURTLE_SCUTE", 6L);
        byName.put("ARMADILLO_SCUTE", 2L);
        byName.put("HONEYCOMB", 1L);
        byName.put("EGG", 1L);
        byName.put("BREEZE_ROD", 15L);
        byName.put("WIND_CHARGE", 2L);
        byName.put("BLAZE_POWDER", 2L);
        byName.put("DRAGON_BREATH", 30L);
        byName.put("ECHO_SHARD", 40L);
        byName.put("HEART_OF_THE_SEA", 120L);
        byName.put("NETHER_STAR", 400L);
        byName.put("TOTEM_OF_UNDYING", 150L);
        byName.put("ELYTRA", 400L);
        byName.put("DRAGON_EGG", 1500L);
        byName.put("TRIDENT", 200L);
        byName.put("SADDLE", 15L);
        byName.put("NAME_TAG", 15L);
        byName.put("ENCHANTED_BOOK", 40L);
        byName.put("ENCHANTED_GOLDEN_APPLE", 300L);
        byName.put("EXPERIENCE_BOTTLE", 5L);
        byName.put("TRIAL_KEY", 25L);
        byName.put("OMINOUS_TRIAL_KEY", 70L);
        byName.put("HEAVY_CORE", 400L);
        byName.put("OMINOUS_BOTTLE", 20L);
        byName.put("RECOVERY_COMPASS", 90L);
        byName.put("DISC_FRAGMENT_5", 20L);

        // -- Farm & forage ------------------------------------------------------
        // Nearly all 1: a coppet is the floor, and a thing you can automate the
        // production of has no business being worth more than the floor.
        byName.put("WHEAT", 1L);
        byName.put("WHEAT_SEEDS", 1L);
        byName.put("BEETROOT_SEEDS", 1L);
        byName.put("MELON_SEEDS", 1L);
        byName.put("PUMPKIN_SEEDS", 1L);
        byName.put("TORCHFLOWER_SEEDS", 25L);
        byName.put("PITCHER_POD", 25L);
        byName.put("CARROT", 1L);
        byName.put("POTATO", 1L);
        byName.put("POISONOUS_POTATO", 1L);
        byName.put("BEETROOT", 1L);
        byName.put("SUGAR_CANE", 1L);
        byName.put("CACTUS", 1L);
        byName.put("KELP", 1L);
        byName.put("SEAGRASS", 1L);
        byName.put("NETHER_WART", 1L);
        byName.put("CHORUS_FRUIT", 2L);
        byName.put("GLOW_BERRIES", 1L);
        byName.put("SWEET_BERRIES", 1L);
        byName.put("COCOA_BEANS", 1L);
        byName.put("APPLE", 1L);
        byName.put("MELON_SLICE", 1L);
        byName.put("BROWN_MUSHROOM", 1L);
        byName.put("RED_MUSHROOM", 1L);
        byName.put("BEEF", 1L);
        byName.put("PORKCHOP", 1L);
        byName.put("CHICKEN", 1L);
        byName.put("MUTTON", 1L);
        byName.put("RABBIT", 1L);
        byName.put("COD", 1L);
        byName.put("SALMON", 1L);
        byName.put("TROPICAL_FISH", 2L);
        byName.put("PUFFERFISH", 2L);
        byName.put("FLINT", 1L);
        byName.put("CLAY_BALL", 1L);
        byName.put("BRICK", 1L);
        byName.put("STICK", 1L);
        byName.put("BAMBOO", 1L);

        // -- Brewing has no Recipe API, so its output has to be asserted --------
        byName.put("POTION", 6L);
        byName.put("SPLASH_POTION", 8L);
        byName.put("LINGERING_POTION", 10L);
        byName.put("TIPPED_ARROW", 1L);

        // -- Loot and drops nothing crafts -------------------------------------
        // Without these the shape heuristic answers, and it only knows stack size: it
        // would price a water bucket like a diamond pickaxe and a mob head like dirt.
        byName.put("CHAINMAIL_HELMET", 6L);
        byName.put("CHAINMAIL_CHESTPLATE", 10L);
        byName.put("CHAINMAIL_LEGGINGS", 9L);
        byName.put("CHAINMAIL_BOOTS", 5L);
        byName.put("COPPER_HORSE_ARMOR", 8L);
        byName.put("IRON_HORSE_ARMOR", 14L);
        byName.put("GOLDEN_HORSE_ARMOR", 20L);
        byName.put("DIAMOND_HORSE_ARMOR", 60L);
        byName.put("COPPER_NAUTILUS_ARMOR", 8L);
        byName.put("IRON_NAUTILUS_ARMOR", 14L);
        byName.put("GOLDEN_NAUTILUS_ARMOR", 20L);
        byName.put("DIAMOND_NAUTILUS_ARMOR", 60L);
        byName.put("GOAT_HORN", 30L);
        byName.put("SKELETON_SKULL", 40L);
        byName.put("WITHER_SKELETON_SKULL", 90L);
        byName.put("ZOMBIE_HEAD", 40L);
        byName.put("CREEPER_HEAD", 40L);
        byName.put("PIGLIN_HEAD", 40L);
        byName.put("DRAGON_HEAD", 500L);
        byName.put("PLAYER_HEAD", 20L);
        byName.put("SNOWBALL", 1L);
        byName.put("FIREWORK_STAR", 2L);
        byName.put("AMETHYST_CLUSTER", 5L);
        byName.put("BELL", 30L);
        byName.put("SCULK_SENSOR", 16L);
        byName.put("SCULK_SHRIEKER", 22L);
        byName.put("SCULK_CATALYST", 32L);
        byName.put("TURTLE_EGG", 12L);
        byName.put("SNIFFER_EGG", 90L);
        byName.put("SUSPICIOUS_SAND", 2L);
        byName.put("SUSPICIOUS_GRAVEL", 2L);
        byName.put("CHIPPED_ANVIL", 60L);
        byName.put("DAMAGED_ANVIL", 50L);
        byName.put("PETRIFIED_OAK_SLAB", 40L);
        byName.put("FILLED_MAP", 2L);
        byName.put("WRITTEN_BOOK", 3L);
        byName.put("KNOWLEDGE_BOOK", 50L);
        byName.put("END_PORTAL_FRAME", 500L);
        byName.put("SPAWNER", 1500L);
        byName.put("TRIAL_SPAWNER", 1500L);
        byName.put("VAULT", 1500L);

        // Buckets: the bucket itself is craftable, what is in it is not.
        byName.put("WATER_BUCKET", 6L);
        byName.put("LAVA_BUCKET", 7L);
        byName.put("MILK_BUCKET", 6L);
        byName.put("POWDER_SNOW_BUCKET", 7L);
        byName.put("COD_BUCKET", 8L);
        byName.put("SALMON_BUCKET", 8L);
        byName.put("PUFFERFISH_BUCKET", 9L);
        byName.put("TROPICAL_FISH_BUCKET", 9L);
        byName.put("TADPOLE_BUCKET", 12L);
        byName.put("AXOLOTL_BUCKET", 30L);

        for (Map.Entry<String, Long> entry : byName.entrySet()) {
            // Looked up by name so that a material missing from this server's version is
            // skipped rather than failing class initialisation.
            Material material = Material.getMaterial(entry.getKey());
            if (material != null) seeds.put(material, entry.getValue());
        }
        return Map.copyOf(seeds);
    }
}
