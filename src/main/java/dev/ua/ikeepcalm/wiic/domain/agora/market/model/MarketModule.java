package dev.ua.ikeepcalm.wiic.domain.agora.market.model;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketServices;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.service.ContainmentService;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.model.EntranceItem;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.listener.EntranceListener;
import dev.ua.ikeepcalm.wiic.domain.agora.entrance.service.EntranceService;
import dev.ua.ikeepcalm.wiic.domain.agora.integration.LandsHook;
import dev.ua.ikeepcalm.wiic.domain.agora.integration.CourierHook;
import dev.ua.ikeepcalm.wiic.domain.agora.market.listener.MarketContainmentListener;
import dev.ua.ikeepcalm.wiic.domain.agora.market.listener.MarketPlayerListener;
import dev.ua.ikeepcalm.wiic.domain.agora.market.listener.MarketProtectionListener;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.listener.PlotShopListener;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.listener.PlotWandListener;
import dev.ua.ikeepcalm.wiic.domain.agora.npc.listener.MarketNpcListener;
import dev.ua.ikeepcalm.wiic.domain.agora.npc.model.source.MarketNpcRole;
import dev.ua.ikeepcalm.wiic.domain.agora.npc.service.MarketNpcService;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.coi.ItemInspector;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.CourierService;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.ExpirySweeper;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.journal.JournalRecovery;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.LedgerService;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.ListingService;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.journal.MarketJournal;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketPurchaseService;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.service.PlotService;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.service.PlotShopService;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.PriceGuide;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.SaleNotifier;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.StashService;
import dev.ua.ikeepcalm.wiic.gui.market.BrokerGUI;
import dev.ua.ikeepcalm.wiic.gui.market.CourierPostGUI;
import dev.ua.ikeepcalm.wiic.gui.market.InformantSearchGUI;
import dev.ua.ikeepcalm.wiic.gui.market.LedgerGUI;
import dev.ua.ikeepcalm.wiic.gui.market.MarketBrowseGUI;
import dev.ua.ikeepcalm.wiic.gui.market.PlotManageGUI;
import dev.ua.ikeepcalm.wiic.gui.market.StashGUI;
import dev.ua.ikeepcalm.wiic.gui.shop.ShopGUI;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
import lombok.Getter;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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

        if (config.world() == null) {
            plugin.getLogger().warning("Market world '" + config.worldName()
                    + "' is not loaded — entrances will refuse to teleport until it is (check the Worlds plugin)");
        }

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
            plugin.getLogger().warning("Citizens not found — market NPCs are disabled (GUIs reachable via /wiicmarket open)");
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
