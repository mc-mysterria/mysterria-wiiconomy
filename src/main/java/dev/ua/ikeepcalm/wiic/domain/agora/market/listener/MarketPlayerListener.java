package dev.ua.ikeepcalm.wiic.domain.agora.market.listener;

import dev.ua.ikeepcalm.wiic.domain.agora.db.LedgerDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.db.StashDao;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.SaleNotifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Connects the market to a player's comings and goings: greeting anyone who has proceeds
 * or parcels waiting underground, and dropping their notification cooldown on the way out.
 */
public class MarketPlayerListener implements Listener {

    /**
     * Long enough after login that the greeting isn't buried under join spam.
     */
    private static final long GREETING_DELAY_TICKS = 60L;

    private final MarketDatabase db;
    private final SaleNotifier notifier;
    private final Plugin plugin;

    public MarketPlayerListener(Plugin plugin, MarketDatabase db, SaleNotifier notifier) {
        this.plugin = plugin;
        this.db = db;
        this.notifier = notifier;
    }

    /**
     * Business done while they were offline produced no rumour; this is the catch-up.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            db.submitThenMain(conn -> new long[]{
                            StashDao.countUnclaimed(conn, player.getUniqueId()),
                            LedgerDao.sumUnclaimed(conn, player.getUniqueId())
                    },
                    waiting -> {
                        if (player.isOnline()) notifier.greetReturning(player, (int) waiting[0], waiting[1]);
                    },
                    error -> { /* a greeting is not worth a console line */ });
        }, GREETING_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        notifier.forget(event.getPlayer().getUniqueId());
    }
}
