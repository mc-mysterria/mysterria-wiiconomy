package dev.ua.ikeepcalm.market.npc.listeners;

import dev.ua.ikeepcalm.market.auction.inventories.MarketInventory;
import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

public class NpcListener implements Listener {
    private final NamespacedKey shopkeeperKey;

    public NpcListener() {
        shopkeeperKey = new NamespacedKey(WIIC.INSTANCE, "shopkeeper");
    }
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getPersistentDataContainer().has(shopkeeperKey)) {
            event.setCancelled(true);
            new MarketInventory().open(event.getPlayer());
        }
    }

    public NamespacedKey getShopkeeperKey() { return shopkeeperKey; }
}
