package dev.ua.ikeepcalm.wiic.gui.market;

import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketServices;
import dev.ua.ikeepcalm.wiic.domain.agora.db.ListingDao;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.Listing;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.coi.ItemInspector;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The bazaar's main browse screen (Clerk NPC): paginated ACTIVE listings with
 * category tabs (config categories + implicit "beyonder"), newest/cheapest sort,
 * and an anvil name search. Data loads on the DB thread; the screen renders on
 * the main thread when the page arrives.
 *
 * <p>Configured via {@code browse-gui} in {@code market.yml}: {@code title},
 * {@code background}, {@code items.listing.lore} (appended to every listing icon,
 * placeholders {@code %price% %seller% %expires%}), {@code items.back/previous-page/
 * next-page/page-indicator/sort/search/tab-all/tab-beyonder}, and per-category
 * {@code icon} materials under {@code categories.<id>} in the classifier section.
 */
public class MarketBrowseGUI {

    /** Immutable browse filter; also used by the Informant and plot vendors. */
    public record Filter(@Nullable String category, @Nullable String pathway,
                         @Nullable Integer sequence, @Nullable String plotId, @Nullable String query,
                         boolean coiUnaligned) {
        public static Filter all() {
            return new Filter(null, null, null, null, null, false);
        }

        public static Filter category(String category) {
            return new Filter(category, null, null, null, null, false);
        }

        public static Filter pathway(String pathway, @Nullable Integer sequence) {
            return new Filter(null, pathway, sequence, null, null, false);
        }

        public static Filter plot(String plotId) {
            return new Filter(null, null, null, plotId, null, false);
        }

        public static Filter search(String query) {
            return new Filter(null, null, null, null, query, false);
        }

        /** Beyonder goods CoI gives no pathway — ingredients. */
        public static Filter ingredients() {
            return new Filter(null, null, null, null, null, true);
        }
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PAGE_SIZE = 36;
    private static final int CONTENT_START = 9;
    private static final int BACK_SLOT = 0;
    private static final int SEARCH_SLOT = 7;
    private static final int SORT_SLOT = 8;
    private static final int PREVIOUS_SLOT = 47;
    private static final int PAGE_INDICATOR_SLOT = 49;
    private static final int NEXT_SLOT = 51;

    private final MarketServices services;
    private final @Nullable Runnable onBack;

    public MarketBrowseGUI(MarketServices services, @Nullable Runnable onBack) {
        this.services = services;
        this.onBack = onBack;
    }

    public void open(Player player) {
        open(player, Filter.all(), ListingDao.Sort.NEWEST, 0);
    }

    public void open(Player player, Filter filter, ListingDao.Sort sort, int page) {
        services.db().submitThenMain(conn -> {
            if (filter.query() != null) {
                return ListingDao.search(conn, filter.query(), PAGE_SIZE + 1);
            }
            if (filter.pathway() != null) {
                return ListingDao.searchByPathway(conn, filter.pathway(), filter.sequence(), PAGE_SIZE + 1);
            }
            if (filter.coiUnaligned()) {
                return ListingDao.searchUnalignedCoi(conn, PAGE_SIZE + 1);
            }
            return ListingDao.browse(conn, filter.category(), null, filter.plotId(),
                    sort, PAGE_SIZE + 1, page * PAGE_SIZE);
        }, listings -> render(player, filter, sort, page, listings), error -> {
            player.sendMessage(MM.deserialize(services.config().message("market-error",
                    "<red>The market ledgers are in disarray. Try again later.")));
        });
    }

    private void render(Player player, Filter filter, ListingDao.Sort sort, int page, List<Listing> fetched) {
        ConfigurationSection config = services.config().guiSection("browse-gui");
        boolean hasNext = fetched.size() > PAGE_SIZE;
        List<Listing> listings = hasNext ? fetched.subList(0, PAGE_SIZE) : fetched;

        Material bg = WalletConfig.getThemeBackground(player.getUniqueId(), GuiUtil.backgroundMaterial(config));
        Gui gui = Gui.builder()
                .setStructure(
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #")
                .addIngredient('#', GuiUtil.emptyPane(bg))
                .build();

        if (onBack != null) {
            gui.setItem(BACK_SLOT, configButton(config, "items.back", player, Material.ARROW, "<gray>ʙᴀᴄᴋ",
                    () -> onBack.run()));
        }

        buildCategoryTabs(gui, config, player, filter, sort);

        gui.setItem(SEARCH_SLOT, configButton(config, "items.search", player, Material.COMPASS, "<gray>sᴇᴀʀᴄʜ",
                () -> openSearch(player, filter, sort)));

        String sortName = sort == ListingDao.Sort.NEWEST ? "newest" : "cheapest";
        ItemStack sortBtn = configItem(config, "items.sort", player, Material.HOPPER, "<gray>sᴏʀᴛ: <gold>%sort%",
                Map.of("%sort%", sortName));
        gui.setItem(SORT_SLOT, Item.builder().setItemProvider(sortBtn)
                .addClickHandler(_ -> open(player, filter,
                        sort == ListingDao.Sort.NEWEST ? ListingDao.Sort.CHEAPEST : ListingDao.Sort.NEWEST, 0))
                .build());

        List<String> loreTemplate = config != null ? config.getStringList("items.listing.lore") : List.of();
        int slot = CONTENT_START;
        for (Listing listing : listings) {
            ItemStack display = buildListingIcon(listing, loreTemplate, player);
            gui.setItem(slot, Item.builder().setItemProvider(display)
                    .addClickHandler(_ -> new ListingDetailGUI(services, () -> open(player, filter, sort, page))
                            .open(player, listing.id()))
                    .build());
            slot++;
        }

        if (page > 0) {
            gui.setItem(PREVIOUS_SLOT, configButton(config, "items.previous-page", player, Material.ARROW,
                    "<gray>ᴘʀᴇᴠɪᴏᴜs", () -> open(player, filter, sort, page - 1)));
        }
        if (hasNext) {
            gui.setItem(NEXT_SLOT, configButton(config, "items.next-page", player, Material.ARROW,
                    "<gray>ɴᴇxᴛ", () -> open(player, filter, sort, page + 1)));
        }
        ItemStack indicator = configItem(config, "items.page-indicator", player, Material.PAPER,
                "<gray>ᴘᴀɢᴇ <gold>%page%", Map.of("%page%", String.valueOf(page + 1)));
        gui.setItem(PAGE_INDICATOR_SLOT, Item.builder().setItemProvider(indicator).build());

        String titleStr = config != null ? config.getString("title", "Underground Market") : "Underground Market";
        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, Map.of())))
                .build()
                .open();
    }

    private void buildCategoryTabs(Gui gui, @Nullable ConfigurationSection config, Player player,
                                   Filter active, ListingDao.Sort sort) {
        int slot = 1;
        gui.setItem(slot++, configButton(config, "items.tab-all", player, Material.CHEST, "<gold>ᴀʟʟ ɢᴏᴏᴅs",
                () -> open(player, Filter.all(), sort, 0)));

        ConfigurationSection categories = services.config().categories();
        if (categories != null) {
            for (String id : categories.getKeys(false)) {
                if (slot >= SEARCH_SLOT - 1) break;
                ConfigurationSection section = categories.getConfigurationSection(id);
                Material icon = section != null
                        ? materialOr(section.getString("icon"), Material.BOOK)
                        : Material.BOOK;
                ItemStack tab = plainItem(icon, "<gray>" + GuiUtil.prettify(id));
                gui.setItem(slot++, Item.builder().setItemProvider(tab)
                        .addClickHandler(_ -> open(player, Filter.category(id), sort, 0))
                        .build());
            }
        }
        gui.setItem(slot, configButton(config, "items.tab-beyonder", player, Material.ENCHANTED_BOOK,
                "<dark_purple>ʙᴇʏᴏɴᴅᴇʀ ɢᴏᴏᴅs",
                () -> open(player, Filter.category(ItemInspector.CATEGORY_BEYONDER), sort, 0)));
    }

    private void openSearch(Player player, Filter filter, ListingDao.Sort sort) {
        String[] latest = {""};
        boolean[] navigated = {false};
        Gui upperGui = Gui.builder()
                .setStructure("i # r")
                .addIngredient('i', Item.builder().setItemProvider(new ItemStack(Material.PAPER)).build())
                .addIngredient('#', GuiUtil.emptyPane(Material.GRAY_STAINED_GLASS_PANE))
                .addIngredient('r', Item.builder().setItemProvider(new ItemStack(Material.COMPASS))
                        .addClickHandler(_ -> {
                            if (latest[0].isBlank()) return;
                            navigated[0] = true;
                            open(player, Filter.search(latest[0].trim()), sort, 0);
                        })
                        .build())
                .build();

        ConfigurationSection config = services.config().guiSection("browse-gui");
        String titleStr = config != null ? config.getString("search-title", "Search the bazaar") : "Search the bazaar";
        xyz.xenondevs.invui.window.AnvilWindow.builder()
                .setViewer(player)
                .setUpperGui(upperGui)
                .setTitle(MM.deserialize(titleStr))
                .setTextFieldAlwaysEnabled(true)
                .setResultAlwaysValid(true)
                .addRenameHandler(text -> latest[0] = text)
                .addCloseHandler(_ -> {
                    if (!navigated[0]) open(player, filter, sort, 0);
                })
                .build()
                .open();
    }

    private ItemStack buildListingIcon(Listing listing, List<String> loreTemplate, Player viewer) {
        ItemStack restored = restoreItem(listing);
        boolean snapshotOnly = restored == null;
        ItemStack display = snapshotOnly
                ? new ItemStack(listing.material(),
                        Math.clamp(listing.amount(), 1, listing.material().getMaxStackSize()))
                : restored;
        if (!snapshotOnly) {
            display.setAmount(Math.clamp(listing.amount(), 1, display.getMaxStackSize()));
        }

        String price = plain(CoinUtil.getFormattedPrice(clampToInt(listing.price())));
        String expires = formatRemaining(listing.expiresAt() - System.currentTimeMillis());
        display.editMeta(meta -> {
            // The restored item brings its own name, styled the way its own plugin styled
            // it; the snapshot column is plain text and would flatten the colour out of it.
            if (!meta.hasDisplayName() && listing.displayName() != null && !listing.displayName().isEmpty()) {
                meta.displayName(Component.text(listing.displayName()).decoration(TextDecoration.ITALIC, false));
            }
            // Only the fallback icon needs help standing out — a restored item already
            // carries its own model, glint and trim.
            if (snapshotOnly && listing.coiItem()) meta.setEnchantmentGlintOverride(true);

            // Market lines are appended, never substituted: an item's own lore is part of
            // what a buyer is judging, and a beyonder item's lore is most of its identity.
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            List<String> template = loreTemplate.isEmpty()
                    ? List.of("", "<gold>%price%", "<dark_gray>ᴇxᴘɪʀᴇs ɪɴ %expires%")
                    : loreTemplate;
            String seller = sellerLabel(services.config(), listing, viewer);
            for (String line : template) {
                String resolved = line.replace("%price%", price)
                        .replace("%seller%", seller)
                        .replace("%expires%", expires);
                lore.add(resolved.isEmpty() ? Component.empty()
                        : MM.deserialize(resolved).decoration(TextDecoration.ITALIC, false));
            }
            if (listing.coiPathway() != null) {
                lore.add(MM.deserialize("<dark_purple>" + GuiUtil.prettify(listing.coiPathway())
                                + (listing.coiSequence() != null ? " <dark_gray>sᴇǫ " + listing.coiSequence() : ""))
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        });
        return display;
    }

    /**
     * The listed item itself, so custom item models, trims and components survive onto
     * the shelf — an icon rebuilt from the snapshot columns renders a beyonder
     * ingredient as the plain material CoI happened to base it on.
     *
     * @return null when the blob is missing or unreadable, leaving the caller to fall
     *         back to the snapshot rather than dropping the listing off the shelf.
     */
    private static @Nullable ItemStack restoreItem(Listing listing) {
        byte[] bytes = listing.itemBytes();
        if (bytes == null || bytes.length == 0) return null;
        try {
            ItemStack restored = ItemStack.deserializeBytes(bytes);
            return restored == null || restored.getType().isAir() ? null : restored;
        } catch (RuntimeException e) {
            // Written by an older server, or corrupt. The snapshot still describes it.
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Shared small helpers for the market GUI family
    // -------------------------------------------------------------------------

    /**
     * What a listing says about who is selling it: nothing that identifies them.
     *
     * <p>The market's whole proposition is that buyer and seller never learn each other's
     * names, and the 8% cut is what it charges for standing between them. Printing the
     * seller on the shelf sells that position away — the pair can read the label, meet in
     * the overworld and trade the same goods for nothing. So the name never reaches a
     * viewer who isn't the seller.
     *
     * <p>{@code %seller%} stays substituted rather than dropped, because market.yml files
     * already deployed have it in their lore, and an unsubstituted placeholder would print
     * itself verbatim. Owners see their own listings marked, which is not a leak — they
     * already know — and saves them clicking into their own goods.
     */
    static String sellerLabel(MarketConfig config, Listing listing, Player viewer) {
        return listing.sellerUuid().equals(viewer.getUniqueId())
                ? config.message("listing-seller-self", "<dark_gray>ʏᴏᴜʀ ᴏᴡɴ ᴏꜰꜰᴇʀ")
                : config.message("listing-seller-anonymous", "<dark_gray>sᴏᴍᴇᴏɴᴇ");
    }

    public static Item configButton(@Nullable ConfigurationSection config, String path, Player player,
                                    Material fallback, String fallbackName, Runnable onClick) {
        ItemStack item = configItem(config, path, player, fallback, fallbackName, Map.of());
        return Item.builder().setItemProvider(item).addClickHandler(_ -> onClick.run()).build();
    }

    public static ItemStack configItem(@Nullable ConfigurationSection config, String path, Player player,
                                       Material fallback, String fallbackName, Map<String, String> extras) {
        ConfigurationSection section = config != null ? config.getConfigurationSection(path) : null;
        if (section != null) return GuiUtil.createConfigItem(section, player, extras);
        String name = fallbackName;
        for (var entry : extras.entrySet()) name = name.replace(entry.getKey(), entry.getValue());
        return plainItem(fallback, name);
    }

    static ItemStack plainItem(Material material, String miniMessageName) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(MM.deserialize(miniMessageName)
                .decoration(TextDecoration.ITALIC, false)));
        return item;
    }

    static Material materialOr(@Nullable String name, Material fallback) {
        if (name == null) return fallback;
        Material material = Material.matchMaterial(name);
        return material != null ? material : fallback;
    }

    static String formatRemaining(long millis) {
        if (millis <= 0) return "0m";
        Duration duration = Duration.ofMillis(millis);
        long hours = duration.toHours();
        if (hours >= 24) return (hours / 24) + "d " + (hours % 24) + "h";
        if (hours > 0) return hours + "h " + duration.toMinutesPart() + "m";
        return Math.max(1, duration.toMinutes()) + "m";
    }

    static int clampToInt(long value) {
        return Math.clamp(value, 0, Integer.MAX_VALUE);
    }

    static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
