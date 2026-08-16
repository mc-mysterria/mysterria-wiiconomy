package dev.ua.ikeepcalm.wiic.domain.agora.market.model;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.listener.EntranceListener;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.model.EntranceItem;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.service.ContainmentService;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.service.EntranceService;
import dev.ua.ikeepcalm.wiic.domain.agora.integration.CoiGuardListener;
import dev.ua.ikeepcalm.wiic.domain.agora.integration.CourierHook;
import dev.ua.ikeepcalm.wiic.domain.agora.integration.LandsHook;
import dev.ua.ikeepcalm.wiic.domain.agora.integration.WorldsHook;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.CourierService;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.LedgerService;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.ListingService;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.StashService;
import dev.ua.ikeepcalm.wiic.domain.agora.market.listener.MarketContainmentListener;
import dev.ua.ikeepcalm.wiic.domain.agora.market.listener.MarketPlayerListener;
import dev.ua.ikeepcalm.wiic.domain.agora.market.listener.MarketProtectionListener;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketPurchaseService;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketServices;
import dev.ua.ikeepcalm.wiic.domain.agora.npc.listener.MarketNpcListener;
import dev.ua.ikeepcalm.wiic.domain.agora.npc.model.source.MarketNpcRole;
import dev.ua.ikeepcalm.wiic.domain.agora.npc.service.MarketNpcService;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.listener.PlotShopListener;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.listener.PlotWandListener;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.service.PlotService;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.service.PlotShopService;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.ExpirySweeper;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.PriceGuide;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.SaleNotifier;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.coi.ItemInspector;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.journal.JournalRecovery;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.journal.MarketJournal;
import dev.ua.ikeepcalm.wiic.gui.market.*;
import dev.ua.ikeepcalm.wiic.gui.shop.ShopGUI;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
import dev.ua.ikeepcalm.wiic.utils.WorldUtil;
import lombok.Getter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.window.Window;

/**
 * Enables, wires, and shuts down the Underground Market. Everything is optional —
 * if {@code market.yml} says {@code enabled: false} (the shipped default) or the
 * database can't open, the module stays null and the rest of WIIC is untouched.
 *
 * <p>Soft-dependency boundaries live here: {@code MarketNpcService}/{@code
 * MarketNpcListener} (Citizens) and {@code LandsHook} (Lands) are only
 * instantiated — and therefore only classloaded — when their plugin is enabled.
 */
public class MarketModule {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    @Getter
    private final MarketConfig config;
    @Getter
    private final MarketServices services;
    @Getter
    private final @Nullable MarketNpcService npcService;
    /** Admin wand selections for {@code /wiicmarket plot define}. */
    @Getter
    private final PlotWandListener plotWand = new PlotWandListener();
    private final ExpirySweeper sweeper;

    public static @Nullable MarketModule enableIfConfigured(WIIC plugin) {
        MarketConfig config = new MarketConfig(plugin);
        if (!config.enabled()) {
            plugin.getLogger().info("Underground Market disabled in market.yml");
            return null;
        }
        try {
            return new MarketModule(plugin, config);
        } catch (Exception e) {
            plugin.getLogger().severe("Underground Market failed to enable: " + e);
            return null;
        }
    }

    private MarketModule(WIIC plugin, MarketConfig config) throws Exception {
        this.config = config;

        if (config.world() == null) bootstrapWorld(plugin, config);

        MarketDatabase db = new MarketDatabase(plugin);
        db.open();
        MarketJournal journal = new MarketJournal(plugin);
        new JournalRecovery(plugin, config, db, journal).run();

        MarketFeedback feedback = new MarketFeedback(config);
        SaleNotifier notifier = new SaleNotifier(plugin, config, feedback);
        ItemInspector inspector = new ItemInspector(config);
        ListingService listings = new ListingService(plugin, config, db, journal, inspector);
        CourierService courier = createCourierService(plugin, config, db);
        MarketPurchaseService purchases = new MarketPurchaseService(plugin, config, db, journal, courier, notifier);
        StashService stash = new StashService(plugin, db);
        LedgerService ledger = new LedgerService(plugin, db, journal);

        LandsHook lands = createLandsHook(plugin);
        // Containment first: the entrance service must be able to sanction its own
        // teleports before it makes any, or the market would refuse its own front door.
        ContainmentService containment = new ContainmentService(plugin, config, db, feedback);
        EntranceService entrances = new EntranceService(plugin, config, db, lands, feedback, containment);
        entrances.load();
        containment.start();

        // Citizens first: PlotService needs the vendor spawner (or null) at construction.
        if (Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            this.npcService = new MarketNpcService(config);
        } else {
            this.npcService = null;
            plugin.getLogger().warning("Citizens not found — market NPCs are disabled. /wiicmarket open is"
                    + " admin-only, so ORDINARY PLAYERS HAVE NO WAY TO REACH ANY MARKET GUI until Citizens"
                    + " is installed and the NPCs are placed. The entrances will still let them in.");
        }

        PlotService plots = new PlotService(plugin, config, db, inspector, npcService);
        plots.load();

        PlotShopService shops = new PlotShopService(plugin, config, db, plots, inspector, feedback, notifier);
        shops.load();
        plots.onEvicted(shops::forgetPlot);

        PriceGuide prices = new PriceGuide(plugin, config, db, inspector);
        this.services = new MarketServices(config, db, journal, inspector, listings, purchases, stash, ledger,
                entrances, containment, plots, shops, courier, feedback, prices);

        Bukkit.getPluginManager().registerEvents(new EntranceListener(config, entrances, feedback), plugin);
        Bukkit.getPluginManager().registerEvents(new MarketProtectionListener(config, plots), plugin);
        Bukkit.getPluginManager().registerEvents(new MarketContainmentListener(config, containment), plugin);
        Bukkit.getPluginManager().registerEvents(new PlotShopListener(config, shops, feedback), plugin);
        Bukkit.getPluginManager().registerEvents(new MarketPlayerListener(plugin, db, notifier), plugin);
        Bukkit.getPluginManager().registerEvents(this.plotWand, plugin);
        CoiGuardListener coiGuard = CoiGuardListener.createIfAvailable(plugin, config, containment);
        if (coiGuard != null) Bukkit.getPluginManager().registerEvents(coiGuard, plugin);
        EntranceItem.registerRecipe(plugin, config);

        if (npcService != null) {
            Bukkit.getPluginManager().registerEvents(
                    new MarketNpcListener(config, npcService, this::openGui), plugin);
        }

        this.sweeper = new ExpirySweeper(plugin, config, db);
        sweeper.start();
        plugin.getLogger().info("Underground Market enabled (world: " + config.worldName() + ")");
    }

    /**
     * Loads the market world when no world manager has. The level folder survives on
     * disk even when a world manager forgets its registration between restarts, so this
     * is a load rather than a generate — the builds, plots and NPCs come back with it.
     * Off by default: a server whose world manager is reliable should keep owning the
     * world, and creating a folder from a typo'd name would be worse than a warning.
     */
    private static void bootstrapWorld(WIIC plugin, MarketConfig config) {
        if (!config.worldBootstrapEnabled()) {
            plugin.getLogger().warning("Market world '" + config.worldName()
                    + "' is not loaded — entrances will refuse to teleport until it is."
                    + " Load it with your world manager, or set world-bootstrap.enabled in market.yml");
            return;
        }

        // Worlds owns the level when it's installed, so ask it first: a world it loads
        // keeps the key and settings it was registered under, where a WorldCreator would
        // adopt the same folder into Bukkit's own namespace behind Worlds' back.
        World world = null;
        WorldsHook worlds = WorldsHook.createIfAvailable(plugin);
        if (worlds != null) {
            world = worlds.loadOrCreate(config.worldName(), config.worldBootstrapGenerator());
        }
        if (world == null) world = createWorldDirect(plugin, config);
        if (world == null) {
            plugin.getLogger().severe("Failed to load market world '" + config.worldName()
                    + "' — entrances will refuse to teleport");
            return;
        }

        // The created world lands in Bukkit's namespace, which need not be the namespace
        // `world:` is written in; adopting it keeps every lookup resolving either way.
        config.adoptWorld(world);
        String key = WorldUtil.id(world);
        plugin.getLogger().info("Loaded market world '" + world.getName() + "' (" + key + ")");
        if (!WorldUtil.matches(world, config.worldName())) {
            plugin.getLogger().warning("market.yml says world: '" + config.worldName()
                    + "' but the loaded world is '" + key + "'. Set world: \"" + key
                    + "\" so stored entrances and plots keep matching it.");
        }
    }

    /**
     * Fallback for a server without Worlds: load the level folder straight through
     * Bukkit. The world lands in Bukkit's own namespace, which is why the caller checks
     * the resulting key against {@code world:} and says so when they differ.
     */
    private static @Nullable World createWorldDirect(WIIC plugin, MarketConfig config) {
        String folder = config.worldBootstrapFolder();
        String generator = config.worldBootstrapGenerator();
        WorldCreator creator = new WorldCreator(folder)
                .environment(World.Environment.NORMAL)
                .type(WorldType.FLAT)
                .generateStructures(false);
        if (!generator.isEmpty()) creator.generator(generator);
        try {
            return creator.createWorld();
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().severe("Failed to load market world '" + folder + "': " + e
                    + (generator.isEmpty() ? "" : " — is the '" + generator + "' generator plugin installed?"));
            return null;
        }
    }

    /**
     * The courier service, or null when the feature is switched off in market.yml or
     * undead-postmans isn't there to fly anything. Everything downstream treats null as
     * "purchases wait in the stash".
     */
    private static @Nullable CourierService createCourierService(WIIC plugin, MarketConfig config, MarketDatabase db) {
        if (!config.courierEnabled()) return null;
        CourierHook hook;
        try {
            hook = CourierHook.createIfAvailable(plugin);
        } catch (LinkageError e) {
            // CourierHook itself couldn't link — postmans' API classes aren't there at all.
            plugin.getLogger().severe("UndeadPostmans' API is missing from the server: "
                    + e.getMessage() + " — market courier delivery is disabled");
            return null;
        }
        if (hook == null) return null;
        CourierService courier = new CourierService(plugin, config, db, hook);
        courier.load();
        return courier;
    }

    /**
     * The Lands hook, or null when Lands is absent or its installed build no longer matches
     * the API WIIC compiles against (WIIC targets Lands 8.x).
     */
    private static @Nullable LandsHook createLandsHook(WIIC plugin) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Lands")) {
            plugin.getLogger().warning("Lands not found — craftable secret entrances are disabled (hub entrance still works)");
            return null;
        }
        try {
            return new LandsHook(plugin);
        } catch (LinkageError e) {
            plugin.getLogger().severe("Lands is installed but its API is incompatible with WIIC: "
                    + e.getMessage() + " — craftable secret entrances are disabled");
            return null;
        }
    }

    public void shutdown() {
        sweeper.stop();
        services.plots().shutdown();
        services.containment().shutdown();
        services.entrances().shutdown();
        services.db().shutdown();
        // The single-flight guards are static, so they outlive the module across a plugin
        // reload. A continuation dropped during shutdown never gets to release its entry,
        // and the player it belonged to would come back unable to buy, list or claim
        // anything until the whole server restarted.
        MarketPurchaseService.releaseAll();
        ListingService.releaseAll();
        StashService.releaseAll();
        LedgerService.releaseAll();
        PlotService.releaseAll();
        PlotShopService.releaseAll();
    }

    /** GUI dispatch for market NPCs and {@code /wiicmarket open}. */
    public void openGui(Player player, MarketNpcRole role, @Nullable String plotId) {
        switch (role) {
            case BROKER -> new BrokerGUI(services, plotId).open(player);
            case CLERK -> new MarketBrowseGUI(services, null).open(player);
            case BANKER -> openBankerChooser(player);
            case SHOPKEEPER -> new ShopGUI(WIIC.INSTANCE.getShopServices()).open(player);
            case INFORMANT -> new InformantSearchGUI(services).open(player);
            case PLOT_WARDEN -> new PlotManageGUI(services, () -> player.closeInventory()).open(player);
            case PLOT_VENDOR -> {
                if (plotId == null) return;
                var rental = services.plots().rental(plotId);
                // The stall's own renter gets the listing counter; everyone else the storefront.
                if (rental != null && rental.renterUuid().equals(player.getUniqueId())) {
                    new BrokerGUI(services, plotId).open(player);
                } else {
                    new MarketBrowseGUI(services, null).open(player,
                            MarketBrowseGUI.Filter.plot(plotId),
                            dev.ua.ikeepcalm.wiic.domain.agora.db.ListingDao.Sort.NEWEST, 0);
                }
            }
            case COURIER_POST -> new CourierPostGUI(services, player::closeInventory).open(player);
        }
    }

    /** Small chooser between stash pickup and proceeds claim at the Ledger Keeper. */
    private void openBankerChooser(Player player) {
        var section = config.guiSection("banker-gui");
        Material bg = WalletConfig.getThemeBackground(player.getUniqueId(), GuiUtil.backgroundMaterial(section));
        Gui gui = Gui.builder()
                .setStructure("# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #")
                .addIngredient('#', GuiUtil.emptyPane(bg))
                .build();

        gui.setItem(11, MarketBrowseGUI.configButton(section, "items.stash", player,
                Material.CHEST, "<gold>ʏᴏᴜʀ sᴛᴀsʜ",
                () -> new StashGUI(services, () -> openBankerChooser(player)).open(player)));
        gui.setItem(15, MarketBrowseGUI.configButton(section, "items.ledger", player,
                Material.WRITTEN_BOOK, "<gold>ʏᴏᴜʀ ʟᴇᴅɢᴇʀ",
                () -> new LedgerGUI(services, () -> openBankerChooser(player)).open(player)));

        String titleStr = section != null ? section.getString("title", "The Ledger Keeper") : "The Ledger Keeper";
        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, java.util.Map.of())))
                .build()
                .open();
    }
}
