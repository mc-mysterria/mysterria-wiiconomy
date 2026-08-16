package dev.ua.ikeepcalm.wiic.config;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.domain.agora.market.model.MarketBounds;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotRegion;
import dev.ua.ikeepcalm.wiic.utils.WorldUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Loads and exposes {@code market.yml} — the Underground Market's world binding,
 * listing economics, entrance settings, NPC defaults, GUI sections, and messages.
 * Mirrors {@link ShopConfig}'s jar-default merging and reload contract
 * (wired into {@code /wiicmarket reload}).
 */
public class MarketConfig {

    /** Bypasses every market-world restriction: building, containers, plot rights. */
    public static final String ADMIN_PERMISSION = "wiic.market.admin";

    /** Stand-in for a config regex that doesn't compile; matches nothing, ever. */
    private static final Pattern NEVER_MATCHES = Pattern.compile("(?!)");

    private final WIIC plugin;
    private final File file;
    private FileConfiguration config;

    /**
     * Last resolved market world. {@link #isMarketWorld} sits on every block/damage/spawn
     * event in <em>every</em> world, so it must not build strings or scan the world list —
     * it compares references against this and only re-resolves when the reference has gone
     * stale (world unloaded, or {@code world:} changed by a reload).
     */
    private volatile World worldCache;

    /** Compiled {@code regex:} rules from the deny-list and category classifier. */
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    /** {@code coi-ingredients.yml}, loaded on first valuation and dropped on reload. */
    private volatile FileConfiguration ingredientIndex;

    /**
     * Parsed {@code containment.bounds}. {@code boundsParsed} distinguishes "not looked at
     * yet" from "looked at, and there is no envelope defined" — without it an undefined
     * envelope would re-parse the section on every movement check.
     */
    private volatile @Nullable MarketBounds boundsCache;
    private volatile boolean boundsParsed;

    /** Parsed {@code containment.allow-internal-causes}, consulted on every teleport. */
    private volatile @Nullable Set<String> internalCauseCache;

    public MarketConfig(WIIC plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "market.yml");
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            plugin.saveResource("market.yml", false);
        }
        reload();
    }

    public void reload() {
        worldCache = null;
        patternCache.clear();
        ingredientIndex = null;
        boundsCache = null;
        boundsParsed = false;
        internalCauseCache = null;
        config = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("market.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                config.setDefaults(defaults);
                config.options().copyDefaults(true);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to load market.yml defaults: " + e.getMessage());
        }
    }

    public FileConfiguration raw() {
        return config;
    }

    /**
     * Persists runtime changes (e.g. the admin-set exit door) back to market.yml.
     */
    public void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save market.yml: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // World binding
    // -------------------------------------------------------------------------

    public boolean enabled() {
        return config.getBoolean("enabled", false);
    }

    public String worldName() {
        return config.getString("world", "mysterria_market");
    }

    /** The market world, accepting either a plain name or a key in {@code world:}. */
    public @Nullable World world() {
        World cached = worldCache;
        // Bukkit.getWorld(UUID) is a map lookup with no allocation; it also tells us
        // whether the cached reference still belongs to a loaded world.
        if (cached != null && cached == Bukkit.getWorld(cached.getUID())) return cached;
        World resolved = WorldUtil.resolve(worldName());
        worldCache = resolved;
        return resolved;
    }

    /**
     * Adopts a world WIIC loaded itself. {@code world:} may be written in a world
     * manager's namespace ({@code worlds:agora}) while a world WIIC creates lands in
     * Bukkit's ({@code minecraft:agora}), and then {@link #world()} would never resolve
     * the very world it just loaded.
     */
    public void adoptWorld(World world) {
        worldCache = world;
    }

    public boolean worldBootstrapEnabled() {
        return config.getBoolean("world-bootstrap.enabled", false);
    }

    /**
     * Level folder to load. Defaults to {@code world:} minus any namespace — a
     * {@code WorldCreator} takes a folder name, and {@code worlds:agora} is not one.
     */
    public String worldBootstrapFolder() {
        String configured = config.getString("world-bootstrap.folder", "");
        if (configured != null && !configured.isBlank()) return configured.strip();
        String id = worldName();
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** Chunk generator id for the bootstrap load; blank means a flat void. */
    public String worldBootstrapGenerator() {
        String generator = config.getString("world-bootstrap.generator", "");
        return generator == null ? "" : generator.strip();
    }

    /** Hot path — called from every world-scoped listener, in every world. */
    public boolean isMarketWorld(@Nullable World world) {
        if (world == null) return false;
        if (world == worldCache) return true;
        return world == world();
    }

    /**
     * Arrival point inside the market world, or null if the world isn't loaded / not configured.
     */
    public @Nullable Location arrival() {
        World world = world();
        if (world == null) return null;
        List<Double> pos = config.getDoubleList("arrival");
        if (pos.size() < 3) return null;
        float yaw = pos.size() >= 4 ? pos.get(3).floatValue() : 0f;
        return new Location(world, pos.get(0), pos.get(1), pos.get(2), yaw, 0f);
    }

    // -------------------------------------------------------------------------
    // Listing economics
    // -------------------------------------------------------------------------

    public int dailyListingLimit() {
        return Math.max(1, config.getInt("listings.daily-limit", 5));
    }

    public int maxActivePerPlayer() {
        return Math.max(1, config.getInt("listings.max-active-per-player", 15));
    }

    public double listingFeePercent() {
        return Math.max(0, config.getDouble("listings.listing-fee-percent", 2.0));
    }

    public long listingFeeMin() {
        return Math.max(0, config.getLong("listings.listing-fee-min", 1));
    }

    public long listingFee(long price) {
        if (listingFeePercent() <= 0) return 0;
        return Math.max(listingFeeMin(), (long) Math.ceil(price * listingFeePercent() / 100.0));
    }

    public double saleTaxPercent() {
        return Math.max(0, config.getDouble("listings.sale-tax-percent", 8.0));
    }

    public long saleTax(long price) {
        return (long) Math.floor(price * saleTaxPercent() / 100.0);
    }

    public long listingDurationMs() {
        return Math.max(1, config.getLong("listings.duration-hours", 72)) * 60L * 60L * 1000L;
    }

    public long minPrice() {
        return Math.max(1, config.getLong("listings.min-price", 1));
    }

    public long maxPrice() {
        return Math.max(minPrice(), config.getLong("listings.max-price", 1_000_000));
    }

    public List<String> denyMaterials() {
        return config.getStringList("listings.deny.materials");
    }

    public List<String> denyPdcKeys() {
        return config.getStringList("listings.deny.pdc-keys");
    }

    public boolean allowContainers() {
        return config.getBoolean("listings.deny.allow-containers", false);
    }

    // -------------------------------------------------------------------------
    // Sweeper
    // -------------------------------------------------------------------------

    public long sweeperIntervalTicks() {
        return Math.max(20, config.getLong("sweeper.interval-seconds", 300) * 20L);
    }

    public long reservationTimeoutMs() {
        return Math.max(10_000, config.getLong("sweeper.reservation-timeout-seconds", 60) * 1000L);
    }

    public int transactionRetentionDays() {
        return Math.max(1, config.getInt("sweeper.transaction-retention-days", 90));
    }

    // -------------------------------------------------------------------------
    // Categories (classifier: first matching category wins; 'beyonder' is implicit for CoI items)
    // -------------------------------------------------------------------------

    public @Nullable ConfigurationSection categories() {
        return config.getConfigurationSection("categories");
    }

    // -------------------------------------------------------------------------
    // Entrances
    // -------------------------------------------------------------------------

    public boolean entranceCraftable() {
        return config.getBoolean("entrance.craftable", true);
    }

    public List<String> entranceRecipeShape() {
        return config.getStringList("entrance.recipe.shape");
    }

    public @Nullable ConfigurationSection entranceRecipeIngredients() {
        return config.getConfigurationSection("entrance.recipe.ingredients");
    }

    /**
     * Defaults to false. The hub entrance sits on no claim, so nothing but this setting
     * stands between a passer-by and the server's public way into the market.
     */
    public boolean entranceAllowBreak() {
        return config.getBoolean("entrance.allow-break", false);
    }

    /**
     * Whether using a land-tied door re-checks that the player is still trusted there.
     * Off by default: an entrance is placed to serve a place, and owners routinely walk
     * guests in through it.
     */
    public boolean entranceRequireTrust() {
        return config.getBoolean("entrance.require-trust-to-enter", false);
    }

    /**
     * Ticks between stepping through a door and landing on the other side. Long enough
     * that the passage reads as a descent, short enough that nobody feels held.
     */
    public int entranceDescentTicks() {
        return Math.clamp(config.getInt("entrance.descent-ticks", 30), 0, 200);
    }

    /** How long the dark lingers after arrival before it fades. */
    public int entranceFadeTicks() {
        return Math.clamp(config.getInt("entrance.fade-ticks", 40), 0, 200);
    }

    public long entranceValidationIntervalTicks() {
        return Math.max(20 * 60, config.getLong("entrance.validation-interval-minutes", 30) * 60L * 20L);
    }

    /**
     * Exit door block inside the market world (players click it to leave).
     */
    public @Nullable Location exitDoor() {
        World world = world();
        if (world == null) return null;
        List<Integer> pos = config.getIntegerList("entrance.exit-door");
        if (pos.size() < 3) return null;
        return new Location(world, pos.get(0), pos.get(1), pos.get(2));
    }

    // -------------------------------------------------------------------------
    // Containment — the door is the only way in or out
    // -------------------------------------------------------------------------

    public boolean containmentEnabled() {
        return config.getBoolean("containment.enabled", true);
    }

    /**
     * The envelope visitors are kept inside, or null when no admin has drawn one yet.
     *
     * <p>Cached like {@link #worldCache}: it is read on every out-of-bounds decision,
     * including from the move handler, and re-parsing six integers out of a config section
     * thousands of times a second is exactly the kind of thing that shows up in a profile.
     */
    public @Nullable MarketBounds bounds() {
        MarketBounds cached = boundsCache;
        if (cached != null || boundsParsed) return cached;
        cached = MarketBounds.fromConfig(config.getConfigurationSection("containment.bounds"));
        boundsCache = cached;
        boundsParsed = true;
        return cached;
    }

    /** Writes the containment envelope into market.yml (the admin wand flow). */
    public void saveBounds(int[] min, int[] max) {
        config.set("containment.bounds.min", List.of(min[0], min[1], min[2]));
        config.set("containment.bounds.max", List.of(max[0], max[1], max[2]));
        boundsCache = null;
        boundsParsed = false;
        save();
    }

    /** Drops the envelope back to "undefined", leaving only the void floor enforced. */
    public void clearBounds() {
        config.set("containment.bounds.min", List.of());
        config.set("containment.bounds.max", List.of());
        boundsCache = null;
        boundsParsed = false;
        save();
    }

    /** How often the sweeper checks for anyone who ended up outside the envelope. */
    public long containmentPatrolTicks() {
        return Math.clamp(config.getLong("containment.patrol-interval-ticks", 40), 5, 20 * 60);
    }

    /**
     * Teleport causes still permitted <i>within</i> the market. Everything absent is
     * refused — the default pair are vanilla mechanics that move a player half a block and
     * would strand them if blocked.
     */
    public Set<String> containmentAllowedInternalCauses() {
        Set<String> cached = internalCauseCache;
        if (cached != null) return cached;
        cached = config.getStringList("containment.allow-internal-causes").stream()
                .map(cause -> cause.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        internalCauseCache = cached;
        return cached;
    }

    /** Minimum gap between containment refusals reaching the same player. */
    public long containmentMessageCooldownMs() {
        return Math.max(0, config.getLong("containment.message-cooldown-ms", 2500));
    }

    public boolean blockEnderPearls() {
        return config.getBoolean("containment.block-ender-pearls", true);
    }

    public boolean blockChorusFruit() {
        return config.getBoolean("containment.block-chorus-fruit", true);
    }

    public boolean blockElytra() {
        return config.getBoolean("containment.block-elytra", true);
    }

    public boolean blockVehicles() {
        return config.getBoolean("containment.block-vehicles", true);
    }

    // -------------------------------------------------------------------------
    // Prestige plots
    // -------------------------------------------------------------------------

    public boolean plotsEnabled() {
        return config.getBoolean("plots.enabled", true);
    }

    /** Rent per period, in coppets. A money sink like the listing fee. */
    public long plotRentPrice() {
        return Math.max(0, config.getLong("plots.rent-price", 20480));
    }

    public long plotPeriodMs() {
        return Math.max(1, config.getLong("plots.period-days", 7)) * 24L * 60L * 60L * 1000L;
    }

    /** How long an unpaid renter keeps their stall before the upkeep task evicts them. */
    public long plotGraceMs() {
        return Math.max(0, config.getLong("plots.grace-hours", 48)) * 60L * 60L * 1000L;
    }

    public int plotMaxPerPlayer() {
        return Math.max(1, config.getInt("plots.max-per-player", 1));
    }

    /**
     * Cap on a plot's block count. Snapshot capture, eviction's container sweep, and
     * restore all walk the cuboid on the main thread, so an unbounded region would be a
     * server freeze waiting to happen.
     */
    public int plotMaxVolume() {
        return Math.max(1, config.getInt("plots.max-volume", 20000));
    }

    public long plotUpkeepIntervalTicks() {
        return Math.max(20L * 60, config.getLong("plots.upkeep-check-minutes", 30) * 60L * 20L);
    }

    // --- Stall counters (sign on a chest) ---

    public boolean shopsEnabled() {
        return config.getBoolean("plots.shops.enabled", true);
    }

    /** The first line that turns a sign into a counter. */
    public String shopSignTag() {
        return config.getString("plots.shops.sign-tag", "[Market]");
    }

    /**
     * Counters per plot. A cap exists because {@link #plotRegions} are hand-drawn and a
     * renter who wallpapers their stall with signs makes the market unreadable.
     */
    public int shopMaxPerPlot() {
        return Math.max(1, config.getInt("plots.shops.max-per-plot", 12));
    }

    /** How long a quoted counter stays armed before the next click quotes it again. */
    public long shopConfirmMs() {
        return Math.max(500, config.getLong("plots.shops.confirm-seconds", 6) * 1000L);
    }

    /** The four rendered sign lines; placeholders %item%, %price%, %amount%, %owner%. */
    public List<String> shopSignLines() {
        List<String> lines = config.getStringList("plots.shops.sign-lines");
        return lines.isEmpty()
                ? List.of("<dark_purple>сᴛɪйᴋᴀ", "<black>%item%", "<dark_green>%price%", "<dark_gray>%owner%")
                : lines;
    }

    public @Nullable ConfigurationSection plotRegionsSection() {
        return config.getConfigurationSection("plots.regions");
    }

    /** Every well-formed {@code plots.regions} entry, in config order. */
    public List<PlotRegion> plotRegions() {
        ConfigurationSection section = plotRegionsSection();
        if (section == null) return List.of();
        List<PlotRegion> result = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection region = section.getConfigurationSection(id);
            if (region == null) continue;
            PlotRegion parsed = PlotRegion.fromConfig(id, region);
            if (parsed == null) {
                plugin.getLogger().warning("Market plot '" + id
                        + "' has no corners yet — define it with /wiicmarket plot define " + id);
                continue;
            }
            result.add(parsed);
        }
        return result;
    }

    /** Writes a plot's cuboid into market.yml (the admin wand flow). */
    public void savePlotRegion(String id, int[] min, int[] max) {
        config.set("plots.regions." + id + ".min", List.of(min[0], min[1], min[2]));
        config.set("plots.regions." + id + ".max", List.of(max[0], max[1], max[2]));
        if (config.getString("plots.regions." + id + ".display-name") == null) {
            config.set("plots.regions." + id + ".display-name", id);
        }
        save();
    }

    /** Writes a plot's vendor-NPC spot into market.yml. */
    public void savePlotVendorSpot(String id, Location location) {
        config.set("plots.regions." + id + ".vendor-spot", List.of(
                location.getX(), location.getY(), location.getZ(), (double) location.getYaw()));
        save();
    }

    // -------------------------------------------------------------------------
    // Sale rumours
    // -------------------------------------------------------------------------

    public boolean notificationsEnabled() {
        return config.getBoolean("notifications.enabled", true);
    }

    /** Minimum gap between rumours reaching the same seller, however much they sell. */
    public long notificationCooldownMs() {
        return Math.max(0, config.getLong("notifications.cooldown-seconds", 300)) * 1000L;
    }

    // -------------------------------------------------------------------------
    // Valuation (the Fence's opening offer)
    // -------------------------------------------------------------------------

    public boolean valuationEnabled() {
        return config.getBoolean("valuation.enabled", true);
    }

    /** Worth of a Sequence 9 potion, in coppets — the anchor the whole curve hangs off. */
    public long valuationSequenceBase() {
        return Math.max(1, config.getLong("valuation.coi.sequence-9-potion", 256));
    }

    /**
     * Multiplier per step deeper into a pathway. At the default 2.2 a Sequence 0 potion
     * is worth ~1200x a Sequence 9 one — verldors against coppets, which is the intent.
     */
    public double valuationSequenceRatio() {
        return Math.max(1.01, config.getDouble("valuation.coi.sequence-ratio", 2.2));
    }

    public double valuationKindMultiplier(String kind, double def) {
        return Math.max(0, config.getDouble("valuation.coi.kind." + kind.toLowerCase(Locale.ROOT), def));
    }

    /** Sequence assumed for an ingredient missing from {@code coi-ingredients.yml}. */
    public int valuationUnknownIngredientSequence() {
        return Math.clamp(config.getInt("valuation.coi.unknown-ingredient-sequence", 9), 0, 9);
    }

    /** How many past sales of the same kind of goods the guide averages over. */
    public int valuationHistorySize() {
        return Math.clamp(config.getInt("valuation.history.sample-size", 9), 1, 64);
    }

    /**
     * How far the guide leans on observed sales versus the rule-based prior, once
     * enough sales exist. 1.0 trusts the market completely.
     */
    public double valuationHistoryWeight() {
        return Math.clamp(config.getDouble("valuation.history.weight", 0.7), 0, 1);
    }

    public int valuationHistoryMinSales() {
        return Math.max(1, config.getInt("valuation.history.min-sales", 3));
    }

    /**
     * The sequence an ingredient serves, from {@code coi-ingredients.yml}, or null when
     * the file doesn't list it. Main ingredients resolve through {@link #ingredientIsMain}.
     */
    public @Nullable Integer ingredientSequence(String ingredientKey) {
        FileConfiguration index = ingredients();
        if (index.contains("main." + ingredientKey)) return index.getInt("main." + ingredientKey);
        if (index.contains("supplementary." + ingredientKey)) return index.getInt("supplementary." + ingredientKey);
        return null;
    }

    /** Whether the ingredient is a formula's defining component rather than filler. */
    public boolean ingredientIsMain(String ingredientKey) {
        return ingredients().contains("main." + ingredientKey);
    }

    private FileConfiguration ingredients() {
        FileConfiguration loaded = ingredientIndex;
        if (loaded != null) return loaded;
        File file = new File(plugin.getDataFolder(), "coi-ingredients.yml");
        if (!file.exists()) plugin.saveResource("coi-ingredients.yml", false);
        loaded = YamlConfiguration.loadConfiguration(file);
        ingredientIndex = loaded;
        return loaded;
    }

    // -------------------------------------------------------------------------
    // Courier deliveries (undead-postmans)
    // -------------------------------------------------------------------------

    public boolean courierEnabled() {
        return config.getBoolean("courier.enabled", true);
    }

    /**
     * Optional per-delivery sink, in coppets. Zero by default — the horn a player gives
     * up is meant to be the price of the convenience.
     */
    public long courierFee() {
        return Math.max(0, config.getLong("courier.delivery-fee", 0));
    }

    // -------------------------------------------------------------------------
    // NPC defaults / GUI sections / messages
    // -------------------------------------------------------------------------

    public @Nullable ConfigurationSection npcSection(String role) {
        return config.getConfigurationSection("npcs." + role.toLowerCase());
    }

    public @Nullable ConfigurationSection guiSection(String name) {
        return config.getConfigurationSection(name);
    }

    public String message(String key, String def) {
        return config.getString("messages." + key, def);
    }

    public long confirmArmMs() {
        return Math.max(0, config.getLong("confirm-arm-ms", 400));
    }

    /**
     * Deny-list check for material names: plain entries match exactly, {@code regex:}
     * entries match the compiled pattern. Called for every item the Broker mirrors, so
     * patterns are compiled once and cached until reload.
     */
    public boolean isMaterialDenied(String materialName) {
        return matchesAny(denyMaterials(), materialName);
    }

    /**
     * Whether {@code value} matches any rule in {@code rules} ({@code regex:} prefix for
     * patterns, otherwise a case-insensitive equality check). Shared with the category
     * classifier so both sides read the same rule syntax.
     */
    public boolean matchesAny(List<String> rules, String value) {
        for (String rule : rules) {
            if (rule.startsWith("regex:")) {
                if (pattern(rule.substring("regex:".length())).matcher(value).matches()) return true;
            } else if (rule.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The compiled form of a config-supplied regex. A rule the admin mistyped compiles to
     * {@link #NEVER_MATCHES} (logged once) rather than throwing — a broken deny rule must
     * never take a player's listing attempt down with it.
     */
    private Pattern pattern(String regex) {
        return patternCache.computeIfAbsent(regex, raw -> {
            try {
                return Pattern.compile(raw);
            } catch (PatternSyntaxException e) {
                plugin.getLogger().warning("Ignoring malformed market.yml regex '" + raw + "': " + e.getDescription());
                return NEVER_MATCHES;
            }
        });
    }
}
