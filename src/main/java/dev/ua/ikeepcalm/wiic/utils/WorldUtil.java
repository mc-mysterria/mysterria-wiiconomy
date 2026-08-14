package dev.ua.ikeepcalm.wiic.utils;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * World lookups that tolerate both plain names ({@code world}) and namespaced keys
 * ({@code worlds:blackmarket}).
 *
 * <p>Worlds created by world-manager plugins live outside the {@code minecraft}
 * namespace, so {@link World#getName()} and the key string are <em>not</em>
 * interchangeable — a config or DB value written in one form never matches the other.
 * Everything that stores or compares a world id goes through here.
 */
public class WorldUtil {

    private WorldUtil() {
    }

    /** The loaded world for {@code id}, matching by name first and then by key. */
    public static @Nullable World resolve(@Nullable String id) {
        if (id == null || id.isBlank()) return null;
        World byName = Bukkit.getWorld(id);
        if (byName != null) return byName;
        NamespacedKey key = NamespacedKey.fromString(id);
        return key == null ? null : Bukkit.getWorld(key);
    }

    /** Canonical id to persist — the key, which survives name/namespace differences. */
    public static String id(World world) {
        return world.getKey().asString();
    }

    /**
     * Canonicalises an id read back from storage so ids written in either form collapse
     * to the same string. Unloaded worlds keep their stored id verbatim.
     */
    public static String canonical(@Nullable String storedId) {
        World world = resolve(storedId);
        return world != null ? id(world) : String.valueOf(storedId);
    }

    /** Whether {@code world} is the world denoted by {@code id} (name or key form). */
    public static boolean matches(@Nullable World world, @Nullable String id) {
        if (world == null || id == null) return false;
        return world.getName().equals(id) || id(world).equals(id) || world.equals(resolve(id));
    }
}
