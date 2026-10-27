package dev.ua.ikeepcalm.wiic.domain.transition.service;

import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// shortcut: replaces the player's whole post-effect list, so it would wipe another plugin's effect; merge lists if one ever shares a server with it
public class VeilService {

    private static final int STEPS = 20;
    private static final int MAX_HOLD = 200;

    private final Plugin plugin;
    private final Map<UUID, BukkitTask> running = new HashMap<>();

    public VeilService(Plugin plugin) {
        this.plugin = plugin;
    }

    public void close(Player player, int ticks) {
        animate(player, ticks, false);
    }

    public void open(Player player, int ticks) {
        animate(player, ticks, true);
    }

    public void clear(Player player) {
        stop(player);
        player.postEffects().clear();
    }

    private void stop(Player player) {
        BukkitTask task = running.remove(player.getUniqueId());
        if (task != null) task.cancel();
    }

    private void animate(Player player, int ticks, boolean opening) {
        stop(player);
        int length = Math.max(ticks, 1);
        running.put(player.getUniqueId(), new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    stop(player);
                    return;
                }
                double progress = Math.min(tick / (double) length, 1);
                // A closed veil waits for the teleport, but never strands a player in the dark
                if (opening ? progress >= 1 : tick > length + MAX_HOLD) {
                    clear(player);
                    return;
                }
                int step = (int) Math.round((opening ? 1 - progress : progress) * STEPS);
                player.postEffects().set(List.of(Key.key("wiic", String.format("veil_%02d", step))));
                tick++;
            }
        }.runTaskTimer(plugin, 0, 1));
    }
}
