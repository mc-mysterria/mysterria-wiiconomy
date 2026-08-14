package dev.ua.ikeepcalm.wiic.domain.agora.npc.model;

import dev.ua.ikeepcalm.wiic.domain.agora.npc.service.MarketNpcService;
import org.bukkit.Location;

/**
 * The plot layer's whole view of Citizens: spawn and despawn a plot's storefront
 * vendor. Implemented by {@link MarketNpcService} (the Citizens-importing class) and
 * held as a nullable field by {@code PlotService}, so plots work — minus vendor NPCs
 * — on a server without Citizens.
 */
public interface PlotVendorSpawner {

    /**
     * Spawns a {@code PLOT_VENDOR} NPC bound to {@code plotId} and returns its Citizens id.
     */
    int spawnPlotVendor(String plotId, Location location);

    /**
     * Destroys the NPC with {@code npcId}; a no-op when it no longer exists.
     */
    void despawnPlotVendor(int npcId);
}
