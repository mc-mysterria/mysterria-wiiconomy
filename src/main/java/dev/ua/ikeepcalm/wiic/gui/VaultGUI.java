package dev.ua.ikeepcalm.wiic.gui;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.currency.models.WalletData;
import dev.ua.ikeepcalm.wiic.currency.services.PreferencesManager;
import dev.ua.ikeepcalm.wiic.currency.services.PriceAppraiser;
import dev.ua.ikeepcalm.wiic.currency.services.SoldItemsManager;
import dev.ua.ikeepcalm.wiic.utils.CoinUtil;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Vault GUI — shows the player's stored wallet balance and actionable inventory items.
 *
 * <p>Configured via {@code vault-gui} in {@code config.yml}:
 * <ul>
 *   <li>{@code title}                  — MiniMessage title (supports custom-texture chars)</li>
 *   <li>{@code background}             — Material name for the backdrop pane</li>
 *   <li>{@code items.*}                — Static items (e.g. {@code balance}) with standard
 *       item config keys; {@code name}/{@code lore} support {@code %balance%}</li>
 *   <li>{@code wallet-coins-start}     — {@code [x, y]} start slot for wallet coins (fills right)</li>
 *   <li>{@code inventory-items-start}  — {@code [x, y]} start slot for player items (fills right)</li>
 * </ul>
 *
 * <p>Layout with defaults ({@code wallet-coins-start: [3,0]}, {@code inventory-items-start: [1,1]}):
 * <pre>
 *   Row 0:  # [balance] # [VerlDor] [Lick] [Coppet] # # #
 *   Row 1:  # [item₁] … [item₇] #
 *   Row 2:  # # # # # # # # #
 * </pre>
 */
public class VaultGUI {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PriceAppraiser priceAppraiser;
    private final SoldItemsManager soldItemsManager;

    public VaultGUI(PriceAppraiser priceAppraiser, SoldItemsManager soldItemsManager) {
        this.priceAppraiser = priceAppraiser;
        this.soldItemsManager = soldItemsManager;
    }

    public void openVault(Player player, Runnable onClose) {
        Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> openVaultSync(player, onClose));
    }

    private void openVaultSync(Player player, Runnable onClose) {
        ConfigurationSection config = WIIC.INSTANCE.getConfig().getConfigurationSection("vault-gui");
        if (config == null) {
            player.sendMessage(MM.deserialize("<red>Configuration for 'vault-gui' is missing in config.yml!"));
            return;
        }

        WalletData data = VaultUtil.getWalletData(player.getUniqueId()).join();

        // Normalise currency tiers before display
        while (data.getLicks() >= 64) {
            data.setLicks(data.getLicks() - 64);
            data.setVerlDors(data.getVerlDors() + 1);
        }
        while (data.getCoppets() >= 64) {
            data.setCoppets(data.getCoppets() - 64);
            data.setLicks(data.getLicks() + 1);
        }

        // Wallet coins — up to 3 denominations (VerlDor, Lick, Coppet)
        List<ItemStack> walletCoins = new ArrayList<>();
        if (data.getVerlDors() > 0) walletCoins.add(CoinUtil.getVerlDor(data.getVerlDors()));
        if (data.getLicks() > 0) walletCoins.add(CoinUtil.getLick(data.getLicks()));
        if (data.getCoppets() > 0) walletCoins.add(CoinUtil.getCoppet(data.getCoppets()));

        // Player inventory — coins first (deposit), then sellable items (sell)
        List<ItemStack> coins = new ArrayList<>();
        List<ItemStack> sellables = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType() == Material.AIR) continue;
            if (CoinUtil.isCoin(stack)) coins.add(stack);
            else if (priceAppraiser.appraise(stack) > 0) sellables.add(stack);
        }
        List<ItemStack> inventoryItems = new ArrayList<>(coins);
        inventoryItems.addAll(sellables);

        // Flag prevents onClose running when navigating to ActionGUI / SellingGUI
        boolean[] actionClose = {false};

        // Build 3-row GUI — all slots start as the configured background pane
        Material bg = PreferencesManager.getThemeBackground(player.getUniqueId(), GuiUtil.backgroundMaterial(config));
        Gui gui = Gui.builder()
                .setStructure(
                        "# # # # # # # # #",
                        "# # # # # # # # #",
                        "# # # # # # # # #")
                .addIngredient('#', GuiUtil.emptyPane(bg))
                .build();

        // -- Static config-driven items (e.g. balance header) --
        ConfigurationSection items = config.getConfigurationSection("items");
        if (items != null) {
            String formattedBalance = PlainTextComponentSerializer.plainText().serialize(CoinUtil.getFormattedPrice(data.getTotalCoppets()));
            Map<String, String> extras = Map.of("%balance%", formattedBalance);
            for (String key : items.getKeys(false)) {
                ConfigurationSection section = items.getConfigurationSection(key);
                if (section == null) continue;
                int slot = GuiUtil.itemSlot(section);
                if (slot < 0 || slot >= 27) continue;
                ItemStack item = GuiUtil.createConfigItem(section, player, extras);
                gui.setItem(slot, Item.builder().setItemProvider(item).build());
            }
        }

        // -- Wallet coins (withdrawal) --
        int coinStart = GuiUtil.slotIndex(config, "wallet-coins-start");
        if (coinStart < 0) coinStart = 3; // default: col 3, row 0
        for (int i = 0; i < walletCoins.size() && i < 5; i++) {
            final ItemStack coin = walletCoins.get(i);
            final int slotIdx = coinStart + i;
            if (slotIdx >= 27) break;
            gui.setItem(slotIdx, Item.builder()
                    .setItemProvider(coin)
                    .addClickHandler(click -> {
                        ClickType clickType = click.clickType();
                        if (!clickType.isLeftClick() && !clickType.isRightClick()) return;
                        if (actionClose[0]) return;
                        if (player.getInventory().firstEmpty() == -1) {
                            player.sendMessage(MM.deserialize("<red>Your inventory is full!"));
                            return;
                        }
                        actionClose[0] = true;
                        new ActionGUI(player, coin, new ActionGUI.ConfirmationCallback() {
                            @Override
                            public void onConfirm(ItemStack confirmed) {
                                player.getInventory().addItem(confirmed);
                                Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> {
                                    withdraw(player, confirmed);
                                    Bukkit.getScheduler().runTask(WIIC.INSTANCE, () -> openVault(player, onClose));
                                });
                            }

                            @Override
                            public void onCancel() {
                                openVault(player, onClose);
                            }
                        }, onClose).open();
                    })
                    .build());
        }

        // -- Player inventory items (deposit coins / sell items) --
        int invStart = GuiUtil.slotIndex(config, "inventory-items-start");
        if (invStart < 0) invStart = 10; // default: col 1, row 1
        for (int i = 0; i < inventoryItems.size() && i < 7; i++) {
            final ItemStack invItem = inventoryItems.get(i);
            final boolean isCoin = CoinUtil.isCoin(invItem);
            final int slotIdx = invStart + i;
            if (slotIdx >= 27) break;
            gui.setItem(slotIdx, Item.builder()
                    .setItemProvider(invItem)
                    .addClickHandler(_ -> {
                        if (actionClose[0]) return;
                        actionClose[0] = true;

                        if (isCoin) {
                            new ActionGUI(player, invItem, new ActionGUI.ConfirmationCallback() {
                                @Override
                                public void onConfirm(ItemStack confirmed) {
                                    Map<Integer, ItemStack> notRemoved = player.getInventory().removeItem(confirmed);
                                    if (!notRemoved.isEmpty()) {
                                        // Item was dropped before confirming — abort to prevent free deposit
                                        openVault(player, onClose);
                                        return;
                                    }
                                    Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> {
                                        deposit(player, confirmed);
                                        Bukkit.getScheduler().runTask(WIIC.INSTANCE, () -> openVault(player, onClose));
                                    });
                                }

                                @Override
                                public void onCancel() {
                                    openVault(player, onClose);
                                }
                            }, onClose).open();
                        } else {
                            if (player.getInventory().getItemInOffHand().getType() != Material.AIR) {
                                player.sendMessage(MM.deserialize("<red>You must have an empty off-hand to perform this action!"));
                                actionClose[0] = false;
                                return;
                            }
                            new SellingGUI(player, invItem, new SellingGUI.ConfirmationCallback() {
                                @Override
                                public void onConfirm(ItemStack confirmed) {
                                    Map<Integer, ItemStack> notRemoved = player.getInventory().removeItem(confirmed);
                                    if (!notRemoved.isEmpty()) {
                                        // Item was dropped before confirming — abort to prevent free sell
                                        openVault(player, onClose);
                                        return;
                                    }
                                    Bukkit.getScheduler().runTaskAsynchronously(WIIC.INSTANCE, () -> {
                                        sell(player, confirmed);
                                        Bukkit.getScheduler().runTask(WIIC.INSTANCE, () -> openVault(player, onClose));
                                    });
                                }

                                @Override
                                public void onCancel() {
                                    openVault(player, onClose);
                                }
                            }, priceAppraiser, soldItemsManager, onClose).open();
                        }
                    })
                    .build());
        }

        String titleStr = PreferencesManager.getThemeString(
                player.getUniqueId(), "vault-title", config.getString("title", ""));

        Bukkit.getScheduler().runTask(WIIC.INSTANCE, () ->
                Window.builder()
                        .setViewer(player)
                        .setUpperGui(gui)
                        .setTitle(titleStr)
                        .addCloseHandler(_ -> {
                            if (!actionClose[0]) {
                                Bukkit.getScheduler().runTaskLaterAsynchronously(WIIC.INSTANCE, onClose, 5L);
                            }
                            actionClose[0] = false;
                        })
                        .build()
                        .open());
    }

    // -------------------------------------------------------------------------
    // Economy operations
    // -------------------------------------------------------------------------

    private void deposit(Player player, ItemStack item) {
        String type = ItemUtil.getType(item);
        if (type == null) return;
        switch (type) {
            case "goldcoin" -> VaultUtil.deposit(player.getUniqueId(), (long) item.getAmount() * 64 * 64);
            case "silvercoin" -> VaultUtil.deposit(player.getUniqueId(), (long) item.getAmount() * 64);
            case "coppercoin" -> VaultUtil.deposit(player.getUniqueId(), item.getAmount());
        }
    }

    private void withdraw(Player player, ItemStack item) {
        String type = ItemUtil.getType(item);
        if (type == null) return;
        switch (type) {
            case "goldcoin" -> VaultUtil.withdraw(player.getUniqueId(), (long) item.getAmount() * 64 * 64);
            case "silvercoin" -> VaultUtil.withdraw(player.getUniqueId(), (long) item.getAmount() * 64);
            case "coppercoin" -> VaultUtil.withdraw(player.getUniqueId(), item.getAmount());
        }
    }

    private void sell(Player player, ItemStack item) {
        int value = priceAppraiser.appraise(item);
        VaultUtil.deposit(player.getUniqueId(), value);
        soldItemsManager.setSoldValue(player, soldItemsManager.getSoldValue(player) + value);
    }
}
