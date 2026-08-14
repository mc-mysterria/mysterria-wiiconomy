package dev.ua.ikeepcalm.wiic.domain.agora.npc.service;

import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.npc.listener.MarketNpcListener;
import dev.ua.ikeepcalm.wiic.domain.agora.npc.model.source.MarketNpcRole;
import dev.ua.ikeepcalm.wiic.domain.agora.npc.model.PlotVendorSpawner;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.SkinTrait;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.Nullable;

/**
 * Creates and inspects market NPCs. Together with {@link MarketNpcListener} this
 * is the only code importing Citizens — {@code MarketModule} instantiates both
 * only when the Citizens plugin is enabled, so the classes never load without it.
 *
 * <p>NPCs are created once by an admin ({@code /wiicmarket npc create <role>})
 * and persist through Citizens' own saves; the plugin does not respawn them.
 */
public class MarketNpcService implements PlotVendorSpawner {

    public static final String ROLE_KEY = "wiic-market-role";
    public static final String PLOT_KEY = "wiic-market-plot";

    private final MarketConfig config;

    public MarketNpcService(MarketConfig config) {
        this.config = config;
    }

    /** Spawns a market NPC of {@code role} at {@code location} with config-default name/skin. */
    public NPC create(MarketNpcRole role, @Nullable String plotId, Location location) {
        ConfigurationSection defaults = config.npcSection(role.name());
        String rawName = defaults != null
                ? defaults.getString("name", role.name().toLowerCase())
                : role.name().toLowerCase();
        // Citizens takes legacy-formatted names; market.yml uses MiniMessage everywhere.
        String legacyName = LegacyComponentSerializer.legacySection()
                .serialize(MiniMessage.miniMessage().deserialize(rawName));

        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, legacyName);
        npc.data().setPersistent(ROLE_KEY, role.name());
        if (plotId != null) npc.data().setPersistent(PLOT_KEY, plotId);

        String skin = defaults != null ? defaults.getString("skin") : null;
        if (skin != null && !skin.isEmpty()) {
            npc.getOrAddTrait(SkinTrait.class).setSkinName(skin);
        }
        npc.getOrAddTrait(LookClose.class).lookClose(true);
        npc.spawn(location);
        return npc;
    }

    public @Nullable MarketNpcRole roleOf(NPC npc) {
        return MarketNpcRole.fromString(npc.data().get(ROLE_KEY));
    }

    public @Nullable String plotOf(NPC npc) {
        return npc.data().get(PLOT_KEY);
    }

    @Override
    public int spawnPlotVendor(String plotId, Location location) {
        return create(MarketNpcRole.PLOT_VENDOR, plotId, location).getId();
    }

    @Override
    public void despawnPlotVendor(int npcId) {
        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
        if (npc != null) npc.destroy();
    }

    /** Removes the nearest market NPC within {@code radius} blocks of {@code location}. */
    public boolean removeNearest(Location location, double radius) {
        NPC nearest = null;
        double best = radius * radius;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (roleOf(npc) == null || npc.getEntity() == null) continue;
            if (!npc.getEntity().getWorld().equals(location.getWorld())) continue;
            double distance = npc.getEntity().getLocation().distanceSquared(location);
            if (distance <= best) {
                best = distance;
                nearest = npc;
            }
        }
        if (nearest == null) return false;
        nearest.destroy();
        return true;
    }
}
