package dev.ua.ikeepcalm.wiic.domain.agora.integration;

import dev.ua.ikeepcalm.wiic.WIIC;
import net.kyori.adventure.key.Key;
import net.thenextlvl.worlds.Level;
import net.thenextlvl.worlds.WorldRegistry;
import net.thenextlvl.worlds.WorldsAccess;
import net.thenextlvl.worlds.generator.Generator;
import net.thenextlvl.worlds.generator.GeneratorType;
import net.thenextlvl.worlds.preset.Preset;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * The market's only door to the Worlds plugin: loading the market world at startup.
 *
 * <p>This is the single class importing {@code net.thenextlvl.worlds} (compileOnly),
 * so it is also the only class that can fail to load when the plugin is absent —
 * the same soft-dependency boundary {@link CourierHook} and {@link LandsHook} draw.
 * {@link #createIfAvailable} is the gate: it checks the plugin is enabled before
 * touching those types, and treats a {@link LinkageError} — an installed Worlds build
 * older than the API WIIC compiled against — as "unavailable", leaving
 * {@code MarketModule} to fall back to a plain {@code WorldCreator}.
 *
 * <p>Mirrors CircleOfImagination's {@code WorldsWorldProvider}, minus its volatile
 * branch: the market world is always persistent. Nothing here ever deletes or
 * unregisters a level — a market world holds player-built stalls, and the one failure
 * this class must never cause is destroying them.
 */
public class WorldsHook {

    private final WIIC plugin;

    private WorldsHook(WIIC plugin) {
        this.plugin = plugin;
    }

    /** The hook, or null when Worlds is absent or exposes an API WIIC can't drive. */
    public static @Nullable WorldsHook createIfAvailable(WIIC plugin) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Worlds")) return null;
        try {
            // First touch of the API types: probes classloading and signature match.
            WorldsAccess.access();
            return new WorldsHook(plugin);
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().warning("Worlds is installed but its API is incompatible with WIIC: "
                    + e.getMessage() + " — falling back to a plain world load");
            return null;
        }
    }

    /**
     * The market world, loaded through Worlds.
     *
     * <p>Worlds tracks a level as <em>registered</em> and, separately, as
     * <em>enabled</em>; only an enabled one is loaded at startup. A registered level
     * that has been left disabled is therefore present in every way except the one
     * that matters — which is exactly what a market world that "disappears on restart
     * but comes straight back when recreated" looks like. Enabling it is the fix, and
     * it sticks, so this runs once and the market stops vanishing.
     *
     * <p>Only a level Worlds has never heard of is created, and creating against an
     * existing folder adopts it: the builder leaves {@code ignoreLevelData} alone, so
     * an existing {@code level.dat} wins over the settings passed here and the builds
     * inside are never generated over.
     *
     * @param id          {@code world:} from market.yml, as a namespaced key
     * @param generatorId {@code plugin} or {@code plugin:id} of a chunk generator, or
     *                    blank for a flat void level
     * @return the loaded world, or null if Worlds refused
     */
    public @Nullable World loadOrCreate(String id, String generatorId) {
        Key key;
        try {
            key = Key.key(id);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("market.yml world '" + id + "' is not a valid world key: " + e.getMessage());
            return null;
        }

        WorldsAccess access = WorldsAccess.access();
        WorldRegistry registry = access.getWorldRegistry();

        if (registry.isRegistered(key)) {
            if (!registry.isEnabled(key)) {
                registry.setEnabled(key, true);
                plugin.getLogger().info("Market world " + key + " was registered but disabled in Worlds"
                        + " — enabling it so it loads on its own from now on");
            }
            try {
                return access.load(key).join();
            } catch (Exception e) {
                plugin.getLogger().warning("Worlds failed to load the market world " + key + ": " + e.getMessage());
                return null;
            }
        }

        Generator generator = parseGenerator(generatorId);
        Level.Builder builder = Level.builder(key).structures(false);
        if (generator != null) {
            builder = builder.generator(generator);
        } else {
            builder = builder.generatorType(GeneratorType.FLAT.with(Preset.THE_VOID)).seed(0L);
        }

        try {
            Level level = builder.build();
            World world = level.create().join();
            // Registering the generator too — a level Worlds knows only as "some folder"
            // would come back as plain terrain the next time it loads it itself.
            if (world != null) registry.registerIfAbsent(key, level.getDimension(), true, generator);
            return world;
        } catch (Exception e) {
            plugin.getLogger().warning("Worlds failed to create the market world " + key + ": " + e.getMessage());
            return null;
        }
    }

    /** The configured chunk generator, or null for blank / a generator Worlds can't resolve. */
    private @Nullable Generator parseGenerator(String generatorId) {
        if (generatorId.isEmpty()) return null;
        try {
            return Generator.fromString(generatorId);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Market world generator '" + generatorId + "' could not be resolved ("
                    + e.getMessage() + ") — falling back to a flat void level."
                    + " Is that generator's plugin installed?");
            return null;
        }
    }
}
