package dev.ua.ikeepcalm.wiic.gui.market;

import dev.ua.ikeepcalm.wiic.config.WalletConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.market.service.MarketServices;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.CourierContract;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.service.CourierService;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.GuiUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Courier Post: leave a summoning horn here and every market purchase is flown to
 * you by a postman instead of waiting in your stash; take the horn back and it stops.
 *
 * <p>Custody follows the same rule as the Fence's counter — the horn stays in the
 * player's inventory and is only removed inside {@code CourierService.deposit}, which
 * hands it straight back if the escrow row doesn't commit. No virtual inventory, so
 * there is no close-handler dupe surface, and no close handler either (the screens
 * re-open one another).
 *
 * <p>Configured via {@code courier-gui} in {@code market.yml}: {@code title},
 * {@code background}, {@code items.back}, {@code items.withdraw}, {@code items.hint},
 * and {@code lore.contract} ({@code %tier% %seconds% %fee%}).
 */
public class CourierPostGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int CONTENT_START = 9;
    private static final int CONTENT_END = 26;
    private static final int BACK_SLOT = 0;
    private static final int CONTRACT_SLOT = 13;
    private static final int WITHDRAW_SLOT = 22;

    private final MarketServices services;
    private final Runnable onBack;

    public CourierPostGUI(MarketServices services, Runnable onBack) {
        this.services = services;
        this.onBack = onBack;
    }

    public void open(Player player) {
        CourierService courier = services.courier();
        if (courier == null) {
            player.sendMessage(MM.deserialize(services.config().message("courier-unavailable",
                    "<gray>The post is shuttered — no couriers ride today.")));
            onBack.run();
            return;
        }
        courier.contract(player, contract -> render(player, courier, contract));
    }

    private void render(Player player, CourierService courier, @Nullable CourierContract contract) {
        ConfigurationSection config = services.config().guiSection("courier-gui");

        Material bg = WalletConfig.getThemeBackground(player.getUniqueId(), GuiUtil.backgroundMaterial(config));
        Gui gui = Gui.builder()
                .setStructure(
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #")
                .addIngredient('#', GuiUtil.emptyPane(bg))
                .build();

        gui.setItem(BACK_SLOT, MarketBrowseGUI.configButton(config, "items.back", player,
                Material.ARROW, "<gray>ʙᴀᴄᴋ", onBack));

        boolean[] acted = {false};
        if (contract != null) {
            renderContract(gui, config, player, courier, contract, acted);
        } else {
            renderDeposit(gui, config, player, courier, acted);
        }

        String titleStr = config != null ? config.getString("title", "The Courier Post") : "The Courier Post";
        Window.builder()
                .setViewer(player)
                .setUpperGui(gui)
                .setTitle(MM.deserialize(GuiUtil.replacePlaceholders(player, titleStr, Map.of())))
                .build()
                .open();
    }

    /** Active contract: show the escrowed horn with its tier/speed, plus a withdraw button. */
    private void renderContract(Gui gui, @Nullable ConfigurationSection config, Player player,
                                CourierService courier, CourierContract contract, boolean[] acted) {
        ItemStack horn;
        try {
            horn = ItemStack.deserializeBytes(contract.hornItemBytes());
        } catch (Exception e) {
            horn = new ItemStack(Material.GOAT_HORN);
        }

        Map<String, String> extras = Map.of(
                "%tier%", contract.courierType(),
                "%seconds%", String.valueOf(courier.deliverySeconds(contract.courierType())),
                "%fee%", MarketBrowseGUI.plain(CoinUtil.getFormattedPrice(
                        MarketBrowseGUI.clampToInt(services.config().courierFee()))));

        List<String> template = config != null ? config.getStringList("lore.contract") : List.of();
        if (template.isEmpty()) {
            template = List.of("", "<green>ᴏɴ ᴅᴜᴛʏ", "<gray>ᴛɪᴇʀ: <white>%tier%",
                    "<gray>ᴅᴇʟɪᴠᴇʀʏ: <white>%seconds%s", "", "<dark_gray>ᴘᴜʀᴄʜᴀsᴇs ꜰʟʏ sᴛʀᴀɪɢʜᴛ ᴛᴏ ʏᴏᴜ");
        }
        List<String> lore = template;
        ItemStack display = horn.clone();
        display.editMeta(meta -> {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                String text = line;
                for (Map.Entry<String, String> entry : extras.entrySet()) {
                    text = text.replace(entry.getKey(), entry.getValue());
                }
                lines.add(text.isEmpty() ? Component.empty()
                        : MM.deserialize(text).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
        });
        gui.setItem(CONTRACT_SLOT, Item.builder().setItemProvider(display).build());

        gui.setItem(WITHDRAW_SLOT, Item.builder()
                .setItemProvider(MarketBrowseGUI.configItem(config, "items.withdraw", player,
                        Material.BARRIER, "<red>ᴛᴀᴋᴇ ᴛʜᴇ ʜᴏʀɴ ʙᴀᴄᴋ", extras))
                .addClickHandler(_ -> {
                    if (acted[0]) return;
                    acted[0] = true;
                    courier.withdraw(player, success -> {
                        if (success) services.feedback().hornChanged(player);
                        player.sendMessage(MM.deserialize(services.config().message(
                                success ? "courier-withdrawn" : "courier-withdraw-failed",
                                success ? "<gray>The horn is yours again. Purchases will wait in your stash."
                                        : "<red>The clerk cannot find room in your pack for it.")));
                        open(player);
                    });
                })
                .build());
    }

    /** No contract: mirror the player's horns; clicking one escrows it. */
    private void renderDeposit(Gui gui, @Nullable ConfigurationSection config, Player player,
                               CourierService courier, boolean[] acted) {
        List<ItemStack> horns = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) continue;
            if (courier.isHornItem(stack)) horns.add(stack);
        }

        if (horns.isEmpty()) {
            gui.setItem(CONTRACT_SLOT, Item.builder().setItemProvider(
                    MarketBrowseGUI.configItem(config, "items.hint", player, Material.GOAT_HORN,
                            "<gray>ʙʀɪɴɢ ᴀ sᴜᴍᴍᴏɴɪɴɢ ʜᴏʀɴ", Map.of())).build());
            return;
        }

        int slot = CONTENT_START;
        for (ItemStack stack : horns) {
            if (slot > CONTENT_END) break;
            final ItemStack horn = stack;
            gui.setItem(slot, Item.builder().setItemProvider(horn.clone())
                    .addClickHandler(_ -> {
                        if (acted[0]) return;
                        acted[0] = true;
                        courier.deposit(player, horn, result -> {
                            if (result == CourierService.DepositResult.SUCCESS) {
                                services.feedback().hornChanged(player);
                            } else {
                                services.feedback().refused(player);
                            }
                            player.sendMessage(MM.deserialize(depositMessage(result)));
                            open(player);
                        });
                    })
                    .build());
            slot++;
        }
    }

    private String depositMessage(CourierService.DepositResult result) {
        return switch (result) {
            case SUCCESS -> services.config().message("courier-deposited",
                    "<green>The clerk hangs your horn on the wall. Your purchases will be flown to you.");
            case NOT_A_HORN -> services.config().message("courier-not-a-horn",
                    "<red>That is no summoning horn.");
            case ALREADY_CONTRACTED -> services.config().message("courier-already",
                    "<gray>Your horn already hangs on the wall.");
            case ITEM_MISSING -> services.config().message("courier-item-missing",
                    "<red>The horn slipped out of your hands.");
            case UNAVAILABLE -> services.config().message("courier-unavailable",
                    "<gray>The post is shuttered — no couriers ride today.");
            case ERROR -> services.config().message("market-error",
                    "<red>The market ledgers are in disarray. Try again later.");
        };
    }
}
