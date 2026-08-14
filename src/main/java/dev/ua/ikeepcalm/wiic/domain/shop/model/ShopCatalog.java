package dev.ua.ikeepcalm.wiic.domain.shop.model;

import dev.ua.ikeepcalm.wiic.domain.shop.model.source.ShopCategory;
import dev.ua.ikeepcalm.wiic.config.ShopConfig;
import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds and caches the automatic {@code /shop} catalogue: every ordinary building
 * block, minus ores/valuables/functional blocks, grouped into a {@link ShopCategory}
 * and a family (the variant axis within it — wood species, dye color, stone type, …).
 *
 * <p>Nothing here is a hand-maintained item list. The set of purchasable materials is
 * derived from {@link Material#values()} plus {@code shop.yml}'s {@code catalog.*}
 * tuning knobs; the category/family assignment is a fixed set of ordered rules (the
 * same style as {@link dev.ua.ikeepcalm.wiic.domain.wallet.services.PriceAppraiser}'s
 * ore-tag lookup) so it degrades gracefully — a material nobody thought of still gets
 * *a* sensible category, just possibly the wrong one, which {@code /wiic shop-audit}
 * exists to surface and {@code shop.yml}'s allow/deny lists exist to correct.
 *
 * <p>Rebuild with {@link #rebuild()} on {@code /wiic reload} — it re-reads {@link ShopConfig}
 * so allow/deny/regex/price changes take effect without a restart.
 */
public class ShopCatalog {

    // -------------------------------------------------------------------------
    // Hardcoded structural rules — mirrors PriceAppraiser: category *logic* lives
    // in Java, tunable *data* (which exact names, which prices) lives in shop.yml.
    // -------------------------------------------------------------------------

    /** Ores and other valuables that must never appear in the shop, regardless of config. */
    private static final Set<Tag<Material>> ORE_TAGS = Set.of(
            Tag.COAL_ORES, Tag.COPPER_ORES, Tag.IRON_ORES, Tag.GOLD_ORES, Tag.LAPIS_ORES,
            Tag.REDSTONE_ORES, Tag.DIAMOND_ORES, Tag.EMERALD_ORES
    );

    private static final Set<Material> HARD_EXCLUDES = EnumSet.of(
            // Ores / valuables not covered by a Tag
            Material.ANCIENT_DEBRIS, Material.NETHERITE_BLOCK,
            Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK, Material.GOLD_BLOCK, Material.IRON_BLOCK,
            Material.RAW_IRON_BLOCK, Material.RAW_GOLD_BLOCK, Material.RAW_COPPER_BLOCK,
            Material.AMETHYST_CLUSTER, Material.BUDDING_AMETHYST,
            Material.SPONGE, Material.WET_SPONGE,
            Material.OBSIDIAN, Material.CRYING_OBSIDIAN,
            // Creative-only / unobtainable in normal survival play
            Material.BEDROCK, Material.BARRIER, Material.LIGHT,
            Material.STRUCTURE_BLOCK, Material.STRUCTURE_VOID, Material.JIGSAW,
            Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
            Material.SPAWNER, Material.TRIAL_SPAWNER, Material.VAULT,
            Material.END_PORTAL_FRAME, Material.END_PORTAL, Material.END_GATEWAY,
            Material.DRAGON_EGG, Material.REINFORCED_DEEPSLATE, Material.PETRIFIED_OAK_SLAB,
            Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_GRAVEL,
            Material.TEST_BLOCK, Material.TEST_INSTANCE_BLOCK,
            // Functional blocks (storage, mechanics, redstone logic hubs, or anything with its own GUI)
            Material.ENDER_CHEST, Material.BEACON, Material.CONDUIT, Material.RESPAWN_ANCHOR,
            Material.LODESTONE, Material.BELL, Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
            Material.ENCHANTING_TABLE, Material.BREWING_STAND,
            Material.CRAFTING_TABLE, Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.DISPENSER, Material.DROPPER, Material.HOPPER, Material.CRAFTER,
            Material.LOOM, Material.CARTOGRAPHY_TABLE, Material.SMITHING_TABLE,
            Material.STONECUTTER, Material.GRINDSTONE, Material.LECTERN,
            // Sculk (deep dark) functional/valuable blocks — plain SCULK and SCULK_VEIN stay, they're decorative
            Material.SCULK_SENSOR, Material.CALIBRATED_SCULK_SENSOR, Material.SCULK_SHRIEKER, Material.SCULK_CATALYST,
            // Mob-related collectibles, not building material (COPPER_GOLEM_STATUE and its
            // oxidation/wax variants are caught by the "_GOLEM_STATUE" suffix check below)
            Material.TURTLE_EGG, Material.SNIFFER_EGG,
            // Uncolored candle has no sensible family within the COLOR category
            Material.CANDLE
    );

    private static final Set<Tag<Material>> FUNCTIONAL_TAGS = Set.of(
            Tag.SHULKER_BOXES, Tag.CAULDRONS, Tag.PORTALS, Tag.ITEMS_SKULLS
    );

    /** Checked before every other rule — nether/end materials get their own category regardless of tag overlap with stone. */
    private static final List<String> NETHER_END_PREFIXES = List.of(
            "NETHERRACK", "NETHER_BRICK", "RED_NETHER_BRICK", "SOUL_SAND", "SOUL_SOIL",
            "GLOWSTONE", "SHROOMLIGHT", "MAGMA_BLOCK", "BASALT", "BLACKSTONE",
            "CRIMSON_NYLIUM", "WARPED_NYLIUM", "END_STONE", "PURPUR", "CHORUS"
    );

    /**
     * Per-species log/stem/wood-block tags — the one place Bukkit exposes a family
     * grouping directly, so this is checked before falling back to name matching.
     * Deliberately does the same job as {@link #WOOD_FAMILY_TOKENS} for the log tier,
     * but correctly: {@code Tag.OAK_LOGS} contains both {@code OAK_LOG} and
     * {@code STRIPPED_OAK_LOG}, whereas a name-prefix check on {@code "OAK"} misses
     * every stripped variant (its name starts with {@code STRIPPED_}, not {@code OAK_}).
     * Only covers the raw block tier — planks/stairs/doors/etc. of a species have no
     * equivalent Bukkit tag and still need {@link #WOOD_FAMILY_TOKENS}.
     */
    private static final Map<String, Tag<Material>> WOOD_SPECIES_TAGS = Map.ofEntries(
            Map.entry("oak", Tag.OAK_LOGS),
            Map.entry("spruce", Tag.SPRUCE_LOGS),
            Map.entry("birch", Tag.BIRCH_LOGS),
            Map.entry("jungle", Tag.JUNGLE_LOGS),
            Map.entry("acacia", Tag.ACACIA_LOGS),
            Map.entry("dark_oak", Tag.DARK_OAK_LOGS),
            Map.entry("pale_oak", Tag.PALE_OAK_LOGS),
            Map.entry("mangrove", Tag.MANGROVE_LOGS),
            Map.entry("cherry", Tag.CHERRY_LOGS),
            Map.entry("crimson", Tag.CRIMSON_STEMS),
            Map.entry("warped", Tag.WARPED_STEMS),
            Map.entry("bamboo", Tag.BAMBOO_BLOCKS)
    );

    private static final List<String> WOOD_FAMILY_TOKENS = List.of(
            "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "PALE_OAK",
            "MANGROVE", "CHERRY", "CRIMSON", "WARPED", "BAMBOO"
    );

    private static final List<String> STONE_FAMILY_TOKENS = List.of(
            "STONE", "COBBLESTONE", "MOSSY_COBBLESTONE", "GRANITE", "POLISHED_GRANITE",
            "DIORITE", "POLISHED_DIORITE", "ANDESITE", "POLISHED_ANDESITE",
            "DEEPSLATE", "COBBLED_DEEPSLATE", "POLISHED_DEEPSLATE",
            "SANDSTONE", "RED_SANDSTONE", "SMOOTH_SANDSTONE", "CUT_SANDSTONE",
            "QUARTZ", "SMOOTH_QUARTZ", "PRISMARINE", "DARK_PRISMARINE",
            "TUFF", "POLISHED_TUFF", "CALCITE", "DRIPSTONE", "MUD_BRICK", "BRICK"
    );

    private static final List<String> COLOR_FAMILY_TOKENS = List.of(
            "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
            "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"
    );

    private static final List<String> NATURE_FAMILY_TOKENS = List.of(
            "DIRT", "GRASS", "PODZOL", "MYCELIUM", "SAND", "GRAVEL", "CLAY", "MUD",
            "MOSS", "ICE", "SNOW", "CORAL", "LEAVES", "SAPLING", "FLOWER", "VINE"
    );

    private static final List<String> NETHER_END_FAMILY_TOKENS = List.of(
            "NETHERRACK", "NETHER_BRICK", "RED_NETHER_BRICK", "SOUL_SAND", "SOUL_SOIL",
            "GLOWSTONE", "SHROOMLIGHT", "MAGMA", "BASALT", "BLACKSTONE",
            "NYLIUM", "END_STONE", "PURPUR", "CHORUS"
    );

    /** Signs/banners/candles/beds are classified WOOD/COLOR before ever reaching DECORATION — not listed here. */
    private static final List<String> DECORATION_FAMILY_TOKENS = List.of(
            "LANTERN", "CHAIN", "GLASS", "FLOWER_POT"
    );

    private final ShopConfig shopConfig;
    private Map<Material, ShopEntry> entriesByMaterial = Map.of();
    private Map<ShopCategory, List<ShopEntry>> byCategory = Map.of();
    private Map<ShopCategory, Map<String, List<ShopEntry>>> byCategoryAndFamily = Map.of();

    public ShopCatalog(ShopConfig shopConfig) {
        this.shopConfig = shopConfig;
        rebuild();
    }

    /** Re-derives the catalogue from {@link Material#values()} and the current {@link ShopConfig}. */
    public synchronized void rebuild() {
        Set<String> deny = shopConfig.denyNames();
        Set<String> allow = shopConfig.allowNames();
        List<Pattern> excludePatterns = shopConfig.excludeNamePatterns();

        Map<Material, ShopEntry> byMaterial = new EnumMap<>(Material.class);
        for (Material material : Material.values()) {
            if (!isCandidate(material)) continue;

            String name = material.name();
            boolean forceAllow = allow.contains(name);
            if (!forceAllow) {
                if (deny.contains(name)) continue;
                if (isHardExcluded(material)) continue;
                if (excludePatterns.stream().anyMatch(p -> p.matcher(name).find())) continue;
            }

            ShopCategory category = classifyCategory(material);
            String family = classifyFamily(material, category);
            int basePrice = resolveBasePrice(material, category);
            byMaterial.put(material, new ShopEntry(material, category, family, basePrice));
        }

        Map<ShopCategory, List<ShopEntry>> categoryMap = new EnumMap<>(ShopCategory.class);
        Map<ShopCategory, Map<String, List<ShopEntry>>> categoryFamilyMap = new EnumMap<>(ShopCategory.class);
        for (ShopEntry entry : byMaterial.values()) {
            categoryMap.computeIfAbsent(entry.category(), c -> new ArrayList<>()).add(entry);
            categoryFamilyMap
                    .computeIfAbsent(entry.category(), c -> new TreeMap<>())
                    .computeIfAbsent(entry.family(), f -> new ArrayList<>())
                    .add(entry);
        }
        categoryMap.replaceAll((c, list) -> {
            list.sort(Comparator.comparing(ShopEntry::displayName));
            return List.copyOf(list);
        });
        categoryFamilyMap.forEach((c, families) -> families.replaceAll((f, list) -> {
            list.sort(Comparator.comparing(ShopEntry::displayName));
            return List.copyOf(list);
        }));

        this.entriesByMaterial = Map.copyOf(byMaterial);
        this.byCategory = Map.copyOf(categoryMap);
        this.byCategoryAndFamily = categoryFamilyMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> Map.copyOf(e.getValue())));
    }

    // -------------------------------------------------------------------------
    // Lookups
    // -------------------------------------------------------------------------

    public boolean isPurchasable(Material material) {
        return entriesByMaterial.containsKey(material);
    }

    public ShopEntry get(Material material) {
        return entriesByMaterial.get(material);
    }

    public List<ShopCategory> categories() {
        return byCategory.keySet().stream().sorted().toList();
    }

    public List<ShopEntry> entries(ShopCategory category) {
        return byCategory.getOrDefault(category, List.of());
    }

    /** Family keys within a category, in a stable (alphabetical) order. */
    public List<String> families(ShopCategory category) {
        return new ArrayList<>(byCategoryAndFamily.getOrDefault(category, Map.of()).keySet());
    }

    public List<ShopEntry> entries(ShopCategory category, String family) {
        return byCategoryAndFamily.getOrDefault(category, Map.of()).getOrDefault(family, List.of());
    }

    public List<ShopEntry> all() {
        return List.copyOf(entriesByMaterial.values());
    }

    /** Case-insensitive substring search over material display names. */
    public List<ShopEntry> search(String query) {
        String needle = query.toLowerCase(Locale.ROOT).trim();
        if (needle.isEmpty()) return List.of();
        return entriesByMaterial.values().stream()
                .filter(e -> e.material().name().toLowerCase(Locale.ROOT).replace('_', ' ').contains(needle))
                .sorted(Comparator.comparing(ShopEntry::displayName))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Inclusion filter
    // -------------------------------------------------------------------------

    private static boolean isCandidate(Material material) {
        return material.isItem() && material.isBlock() && !material.isLegacy();
    }

    private static boolean isHardExcluded(Material material) {
        if (HARD_EXCLUDES.contains(material)) return true;
        // Every oxidation/wax state of the copper golem statue (COPPER_/EXPOSED_/WEATHERED_/
        // OXIDIZED_/WAXED_*), not just the base one.
        if (material.name().endsWith("_GOLEM_STATUE")) return true;
        for (Tag<Material> tag : ORE_TAGS) if (tag.isTagged(material)) return true;
        for (Tag<Material> tag : FUNCTIONAL_TAGS) if (tag.isTagged(material)) return true;
        return false;
    }

    // -------------------------------------------------------------------------
    // Classification
    // -------------------------------------------------------------------------

    private static ShopCategory classifyCategory(Material material) {
        String name = material.name();

        if (containsAny(name, NETHER_END_PREFIXES)) return ShopCategory.NETHER_END;

        if (Tag.PLANKS.isTagged(material) || Tag.LOGS.isTagged(material) || Tag.WOODEN_SLABS.isTagged(material)
                || Tag.WOODEN_STAIRS.isTagged(material) || Tag.WOODEN_FENCES.isTagged(material)
                || Tag.WOODEN_DOORS.isTagged(material) || Tag.WOODEN_TRAPDOORS.isTagged(material)
                || Tag.WOODEN_BUTTONS.isTagged(material) || Tag.WOODEN_PRESSURE_PLATES.isTagged(material)
                || Tag.ALL_SIGNS.isTagged(material) || Tag.FENCE_GATES.isTagged(material)
                || Tag.BAMBOO_BLOCKS.isTagged(material)) {
            return ShopCategory.WOOD;
        }

        if (Tag.WOOL.isTagged(material) || Tag.WOOL_CARPETS.isTagged(material) || Tag.BEDS.isTagged(material)
                || Tag.BANNERS.isTagged(material) || Tag.CANDLES.isTagged(material) || Tag.CONCRETE_POWDER.isTagged(material)
                || endsWithAny(name, "_CONCRETE", "_TERRACOTTA", "_STAINED_GLASS", "_STAINED_GLASS_PANE", "_GLAZED_TERRACOTTA")) {
            return ShopCategory.COLOR;
        }

        if (Tag.STONE_BRICKS.isTagged(material) || Tag.WALLS.isTagged(material) || Tag.STAIRS.isTagged(material)
                || Tag.SLABS.isTagged(material) || Tag.PRESSURE_PLATES.isTagged(material) || Tag.BUTTONS.isTagged(material)
                || Tag.DOORS.isTagged(material) || Tag.TRAPDOORS.isTagged(material)
                || containsAny(name, STONE_FAMILY_TOKENS)) {
            return ShopCategory.STONE;
        }

        if (Tag.LEAVES.isTagged(material) || Tag.SAPLINGS.isTagged(material) || Tag.FLOWERS.isTagged(material)
                || Tag.SMALL_FLOWERS.isTagged(material) || Tag.CORAL_BLOCKS.isTagged(material) || Tag.CORALS.isTagged(material)
                || containsAny(name, NATURE_FAMILY_TOKENS)) {
            return ShopCategory.NATURE;
        }

        if (Tag.LANTERNS.isTagged(material) || Tag.CHAINS.isTagged(material)
                || material == Material.GLASS || material == Material.GLASS_PANE || material == Material.TINTED_GLASS
                || material == Material.FLOWER_POT) {
            return ShopCategory.DECORATION;
        }

        return ShopCategory.MISC;
    }

    private static String classifyFamily(Material material, ShopCategory category) {
        String family = switch (category) {
            case WOOD -> classifyWoodFamily(material);
            case STONE -> extractFamily(material, STONE_FAMILY_TOKENS);
            case COLOR -> extractFamily(material, COLOR_FAMILY_TOKENS);
            case NATURE -> extractFamily(material, NATURE_FAMILY_TOKENS);
            case NETHER_END -> extractFamily(material, NETHER_END_FAMILY_TOKENS);
            // Tinted glass behaves nothing like ordinary glass in-game (opaque, no light transmission)
            // and must never be grouped under the same "glass" family as GLASS/GLASS_PANE.
            case DECORATION -> material == Material.TINTED_GLASS ? "tinted" : extractFamily(material, DECORATION_FAMILY_TOKENS);
            case MISC -> "misc";
        };
        return family.toLowerCase(Locale.ROOT);
    }

    /**
     * Wood family for anything the {@link #WOOD_SPECIES_TAGS} log/stem tags don't cover
     * (planks, stairs, doors, fences, signs, …) — those have no per-species Bukkit tag,
     * so this falls back to matching the species name against the material name.
     */
    private static String classifyWoodFamily(Material material) {
        for (var species : WOOD_SPECIES_TAGS.entrySet()) {
            if (species.getValue().isTagged(material)) return species.getKey();
        }
        return extractFamily(material, WOOD_FAMILY_TOKENS);
    }

    /**
     * Longest-token-first substring match, so e.g. "DARK_OAK" wins over the shorter
     * "OAK", and modifier-prefixed/suffixed names (STRIPPED_OAK_LOG, ACACIA_LEAVES,
     * POLISHED_BLACKSTONE) still match their base token instead of falling to "misc".
     */
    private static String extractFamily(Material material, List<String> tokens) {
        String name = material.name();
        return tokens.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .filter(name::contains)
                .findFirst()
                .orElse("misc");
    }

    private static boolean containsAny(String name, List<String> tokens) {
        for (String token : tokens) {
            if (name.contains(token)) return true;
        }
        return false;
    }

    private static boolean endsWithAny(String name, String... suffixes) {
        for (String suffix : suffixes) if (name.endsWith(suffix)) return true;
        return false;
    }

    // -------------------------------------------------------------------------
    // Pricing (base, pre-market-index — see ShopPricing for the live unit price)
    // -------------------------------------------------------------------------

    private int resolveBasePrice(Material material, ShopCategory category) {
        Integer override = shopConfig.override(material);
        if (override != null) return override;
        double shapeMultiplier = shopConfig.shapeMultiplier(material.name());
        return Math.max(1, (int) Math.round(shopConfig.categoryPrice(category) * shapeMultiplier));
    }
}
