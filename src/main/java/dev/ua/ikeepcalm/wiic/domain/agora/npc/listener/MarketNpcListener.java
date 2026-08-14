package dev.ua.ikeepcalm.wiic.domain.agora.npc.listener;

import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.npc.model.source.MarketNpcRole;
import dev.ua.ikeepcalm.wiic.domain.agora.npc.service.MarketNpcService;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;

/**
 * Dispatches right-clicks on market NPCs to the GUI layer. Citizens-importing
 * class — registered by {@code MarketModule} only when Citizens is enabled.
 *
 * <p>GUIs open only inside the market world (the physical-presence rule): a
 * market NPC accidentally spawned elsewhere politely refuses.
 */
public class MarketNpcListener implements Listener {

    /** Implemented by {@code MarketModule}; keeps GUI classes out of this package. */
    @FunctionalInterface
    public interface GuiRouter {
        void open(Player player, MarketNpcRole role, @Nullable String plotId);
    }

    private final MarketConfig config;
    private final MarketNpcService npcs;
    private final GuiRouter router;

    public MarketNpcListener(MarketConfig config, MarketNpcService npcs, GuiRouter router) {
        this.config = config;
        this.npcs = npcs;
        this.router = router;
    }

    @EventHandler(ignoreCancelled = true)
    public void onNpcClick(NPCRightClickEvent event) {
        MarketNpcRole role = npcs.roleOf(event.getNPC());
        if (role == null) return;
        Player player = event.getClicker();
        if (!config.isMarketWorld(player.getWorld())) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(
                    config.message("npc-wrong-world", "<gray>They pretend not to see you.")));
            return;
        }
        router.open(player, role, npcs.plotOf(event.getNPC()));
    }
}
